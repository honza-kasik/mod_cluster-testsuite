package org.jboss.modcluster.test.utils;

import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.wildfly.extras.creaper.core.ManagementClient;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.OnlineOptions;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.admin.Administration;
import org.wildfly.extras.creaper.commands.deployments.Deploy;
import org.wildfly.extras.creaper.commands.deployments.Undeploy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Container wrapper for WildFly/EAP workers with mod_cluster subsystem.
 * Builds container from ZIP distribution.
 */
public class WildFlyContainer {

    private static final Logger log = LoggerFactory.getLogger(WildFlyContainer.class);

    private static final int HTTP_PORT = 8080;
    private static final int HTTPS_PORT = 8443;
    private static final int MANAGEMENT_PORT = 9990;

    private final String name;
    private final BalancerContainer balancer;
    private GenericContainer<?> container;
    private OnlineManagementClient managementClient;

    public WildFlyContainer(String name, BalancerContainer balancer) {
        this.name = name;
        this.balancer = balancer;
    }

    public void start() {
        Path zipPath = getWildFlyZipPath();

        if (zipPath != null && zipPath.toFile().exists()) {
            log.info("Building WildFly container from ZIP: {}", zipPath);
            startFromZip(zipPath);
        } else {
            log.info("No ZIP provided, using pre-built container image");
            startFromImage();
        }
    }

    /**
     * Start WildFly from a ZIP distribution (WildFly or EAP).
     * Uses direct docker build to avoid Testcontainers large file transfer issues.
     */
    private void startFromZip(Path zipPath) {
        String zipFileName = zipPath.getFileName().toString();
        String javaVersion = getRequiredJavaVersion(zipFileName);

        // Generate consistent image tag
        String imageTag = ImageBuilder.generateImageTag(zipFileName, javaVersion);

        // Check if image already exists
        if (!ImageBuilder.imageExists(imageTag)) {
            log.info("Building WildFly image from ZIP: {} (this may take a few minutes on first run)", zipFileName);
            ImageBuilder.buildImageFromZip(zipPath, javaVersion, imageTag);
        } else {
            log.info("Using existing image: {}", imageTag);
        }

        // Start container from pre-built image
        startFromPreBuiltImage(imageTag);
    }

    /**
     * Start WildFly from pre-built container image (fallback).
     */
    private void startFromImage() {
        String wildflyVersion = System.getProperty("wildfly.version", "31.0.1.Final");
        String imageName = "quay.io/wildfly/wildfly:" + wildflyVersion;

        startFromPreBuiltImage(imageName);
    }

    /**
     * Start container from a pre-built image (either from registry or locally built).
     */
    private void startFromPreBuiltImage(String imageName) {
        container = new GenericContainer<>(imageName)
                .withNetwork(balancer.getNetwork())
                .withNetworkAliases(name)
                .withExposedPorts(HTTP_PORT, HTTPS_PORT, MANAGEMENT_PORT)
                .withCommand("/opt/wildfly/bin/standalone.sh",
                            "-b", "0.0.0.0",
                            "-bmanagement", "0.0.0.0",
                            "-Djboss.node.name=" + name,
                            "-Djboss.server.default.config=standalone-ha.xml",
                            "-Djboss.modcluster.multicast.address=224.0.1.105",
                            "-Djboss.modcluster.multicast.port=23364")
                .waitingFor(Wait.forLogMessage(".*WFLYSRV0025.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(5)))
                .withLogConsumer(outputFrame ->
                        log.debug("[{}] {}", name.toUpperCase(), outputFrame.getUtf8String().trim()));

        container.start();
        log.info("WildFly worker '{}' started", name);

        // Wait a bit for management interface to be fully ready
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Configure static proxy connection
        configureStaticProxy();

        // Deploy demo application automatically
        deployDemoApp();
    }

    /**
     * Configure static proxy connection to the balancer.
     * Creates an outbound-socket-binding and configures mod_cluster to use it.
     */
    private void configureStaticProxy() {
        try {
            OnlineManagementClient client = getManagementClient();
            Operations ops = getOperations();

            // Step 1: Create outbound-socket-binding to balancer
            log.info("Creating outbound-socket-binding for balancer");

            org.jboss.dmr.ModelNode addSocketBinding = new org.jboss.dmr.ModelNode();
            org.jboss.dmr.ModelNode address = addSocketBinding.get("address");
            address.add("socket-binding-group", "standard-sockets");
            address.add("remote-destination-outbound-socket-binding", "modcluster-balancer");
            addSocketBinding.get("operation").set("add");
            addSocketBinding.get("host").set("balancer");
            addSocketBinding.get("port").set(8080);  // Undertow balancer listens for MCMP on HTTP port

            org.jboss.dmr.ModelNode result = client.execute(addSocketBinding);
            if (!result.get("outcome").asString().equals("success")) {
                log.debug("Socket binding may already exist or failed: {}", result.get("failure-description").asString());
            }

            // Step 2: Set proxy list to use the outbound-socket-binding
            Address mcProxyAddress = Address.subsystem("modcluster").and("proxy", "default");
            org.jboss.dmr.ModelNode proxyList = new org.jboss.dmr.ModelNode();
            proxyList.add("modcluster-balancer");

            org.wildfly.extras.creaper.core.online.ModelNodeResult writeResult =
                ops.writeAttribute(mcProxyAddress, "proxies", proxyList);
            writeResult.assertSuccess();

            // Step 3: Set listener to "default" for HTTP communication with Undertow balancers
            // (default listener attribute is "ajp" which is for Apache httpd)
            org.wildfly.extras.creaper.core.online.ModelNodeResult listenerResult =
                ops.writeAttribute(mcProxyAddress, "listener", "default");
            listenerResult.assertSuccess();

            log.info("Mod_cluster static proxy configured successfully on worker '{}'", name);

            // Wait for the proxy connection to establish
            Thread.sleep(5000);

        } catch (Exception e) {
            log.error("Failed to configure static proxy on worker '{}'", name, e);
        }
    }

    /**
     * Deploy the demo application for testing.
     */
    private void deployDemoApp() {
        try {
            // Copy demo.war from resources
            File demoWar = new File("src/test/resources/deployments/demo.war");
            if (demoWar.exists()) {
                log.info("Deploying demo application to worker '{}' using Creaper", name);
                deploy(demoWar);

                // Wait for mod_cluster to register the context with the balancer
                log.debug("Waiting for context registration with mod_cluster...");
                Thread.sleep(2000);
            } else {
                log.warn("Demo application not found at: {}", demoWar.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("Failed to deploy demo application to worker '{}'", name, e);
        }
    }

    /**
     * Get WildFly ZIP path from system property or environment variable.
     * Priority:
     * 1. System property: wildfly.zip.path
     * 2. Environment variable: WILDFLY_ZIP_PATH
     * 3. Convention: distributions/wildfly-*.zip or distributions/jboss-eap-*.zip
     */
    private Path getWildFlyZipPath() {
        // Check system property
        String zipPath = System.getProperty("wildfly.zip.path");
        if (zipPath != null) {
            return Paths.get(zipPath);
        }

        // Check environment variable
        zipPath = System.getenv("WILDFLY_ZIP_PATH");
        if (zipPath != null) {
            return Paths.get(zipPath);
        }

        // Check conventional location
        File distDir = new File("distributions");
        if (distDir.exists() && distDir.isDirectory()) {
            File[] zips = distDir.listFiles((dir, name) ->
                name.startsWith("wildfly-") && name.endsWith(".zip") ||
                name.startsWith("jboss-eap-") && name.endsWith(".zip"));

            if (zips != null && zips.length > 0) {
                return zips[0].toPath();
            }
        }

        return null;
    }

    /**
     * Determine required Java version based on WildFly/EAP version.
     * Can be overridden via system property: -Dcontainer.java.version=17 or -Dcontainer.java.version=11
     *
     * Auto-detection rules:
     * - WildFly 31+ requires Java 17
     * - WildFly 30 and earlier requires Java 11
     * - EAP 8+ requires Java 17
     * - EAP 7.x requires Java 11
     */
    private String getRequiredJavaVersion(String zipFileName) {
        // Check for explicit override first
        String javaVersionOverride = System.getProperty("container.java.version");
        if (javaVersionOverride != null && !javaVersionOverride.trim().isEmpty()) {
            String javaImage = "openjdk-" + javaVersionOverride;
            log.info("Using Java version from system property: {} ({})", javaVersionOverride, javaImage);
            return javaImage;
        }

        // Auto-detect based on filename
        // Examples: wildfly-31.0.1.Final.zip, wildfly-30.0.1.Final.zip, jboss-eap-8.0.0.zip

        if (zipFileName.startsWith("wildfly-")) {
            // Extract major version (e.g., "31" from "wildfly-31.0.1.Final.zip")
            String versionPart = zipFileName.substring(8); // Remove "wildfly-"
            String majorVersion = versionPart.split("\\.")[0];

            try {
                int major = Integer.parseInt(majorVersion);
                // WildFly 31+ requires Java 17
                String javaVersion = major >= 31 ? "openjdk-17" : "openjdk-11";
                log.info("WildFly {} requires {}", major, javaVersion);
                return javaVersion;
            } catch (NumberFormatException e) {
                log.warn("Could not parse WildFly version from: {}, defaulting to Java 17", zipFileName);
                return "openjdk-17";
            }
        } else if (zipFileName.startsWith("jboss-eap-")) {
            // Extract major version (e.g., "8" from "jboss-eap-8.0.0.zip")
            String versionPart = zipFileName.substring(10); // Remove "jboss-eap-"
            String majorVersion = versionPart.split("\\.")[0];

            try {
                int major = Integer.parseInt(majorVersion);
                // EAP 8+ requires Java 17, EAP 7.x uses Java 11
                String javaVersion = major >= 8 ? "openjdk-17" : "openjdk-11";
                log.info("EAP {} requires {}", major, javaVersion);
                return javaVersion;
            } catch (NumberFormatException e) {
                log.warn("Could not parse EAP version from: {}, defaulting to Java 17", zipFileName);
                return "openjdk-17";
            }
        }

        // Default to Java 17 for unknown formats
        log.warn("Unknown distribution format: {}, defaulting to Java 17", zipFileName);
        return "openjdk-17";
    }

    public void stop() {
        // Close management client
        if (managementClient != null) {
            try {
                managementClient.close();
            } catch (IOException e) {
                log.warn("Error closing management client for worker '{}'", name, e);
            }
            managementClient = null;
        }

        // Stop container
        if (container != null && container.isRunning()) {
            container.stop();
            log.info("WildFly worker '{}' stopped", name);
        }
    }

    public String getHttpUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(HTTP_PORT);
    }

    public String getHttpsUrl() {
        return "https://" + container.getHost() + ":" + container.getMappedPort(HTTPS_PORT);
    }

    public String getManagementUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(MANAGEMENT_PORT);
    }

    public String getInternalHttpUrl() {
        return "http://" + name + ":" + HTTP_PORT;
    }

    public String getName() {
        return name;
    }

    public GenericContainer<?> getContainer() {
        return container;
    }

    /**
     * Get Creaper ManagementClient for this WildFly instance.
     * Creates client on first call, reuses it afterwards.
     */
    public OnlineManagementClient getManagementClient() throws IOException {
        if (managementClient == null) {
            OnlineOptions options = OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .connectionTimeout(30000)
                    .build();

            managementClient = ManagementClient.online(options);
            log.debug("Created management client for worker '{}'", name);
        }
        return managementClient;
    }

    /**
     * Get Creaper Operations helper for this WildFly instance.
     */
    public Operations getOperations() throws IOException {
        return new Operations(getManagementClient());
    }

    /**
     * Get Creaper Administration helper for this WildFly instance.
     */
    public Administration getAdministration() throws IOException {
        return new Administration(getManagementClient());
    }

    /**
     * Execute a CLI command on this WildFly instance using Creaper.
     *
     * @deprecated Use getManagementClient() and Creaper operations instead
     */
    @Deprecated
    public String executeCli(String command) throws Exception {
        OnlineManagementClient client = getManagementClient();
        ModelNode result = client.execute(command);
        return result.toJSONString(false);
    }

    /**
     * Execute a CLI command using shell (fallback for complex commands).
     */
    public String executeCliViaShell(String command) throws Exception {
        var execResult = container.execInContainer(
                "sh", "-c",
                "jboss-cli.sh --connect --controller=localhost:9990 --command='" + command + "'"
        );

        if (execResult.getExitCode() != 0) {
            throw new RuntimeException("CLI command failed: " + execResult.getStderr());
        }

        return execResult.getStdout();
    }

    /**
     * Deploy an application to this worker using Creaper.
     */
    public void deploy(File deploymentFile) throws Exception {
        log.info("Deploying {} to worker '{}' using Creaper", deploymentFile.getName(), name);

        OnlineManagementClient client = getManagementClient();

        // Deploy using Creaper deployment command
        client.apply(new Deploy.Builder(deploymentFile).build());

        log.info("Deployment {} succeeded on worker '{}'", deploymentFile.getName(), name);
    }

    /**
     * Deploy an application using filesystem deployment (alternative method).
     */
    public void deployViaFilesystem(File deploymentFile) throws Exception {
        log.info("Deploying {} to worker '{}' via filesystem", deploymentFile.getName(), name);

        container.copyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(deploymentFile.toPath()),
                "/opt/wildfly/standalone/deployments/" + deploymentFile.getName()
        );

        // Wait for deployment (check for .deployed marker)
        String deploymentName = deploymentFile.getName();
        int maxWait = 30; // seconds
        for (int i = 0; i < maxWait; i++) {
            try {
                var result = container.execInContainer(
                        "sh", "-c",
                        "ls /opt/wildfly/standalone/deployments/" + deploymentName + ".deployed 2>/dev/null"
                );
                if (result.getExitCode() == 0) {
                    log.info("Deployment {} succeeded on worker '{}'", deploymentName, name);
                    return;
                }
            } catch (Exception e) {
                // Ignore, keep waiting
            }
            Thread.sleep(1000);
        }

        log.warn("Deployment {} may not have completed on worker '{}' (timeout)", deploymentName, name);
    }

    /**
     * Undeploy an application from this worker using Creaper.
     */
    public void undeploy(String deploymentName) throws Exception {
        log.info("Undeploying {} from worker '{}'", deploymentName, name);

        OnlineManagementClient client = getManagementClient();
        client.apply(new Undeploy.Builder(deploymentName).build());

        log.info("Undeployed {} from worker '{}'", deploymentName, name);
    }

    /**
     * Check if a deployment exists and is enabled.
     */
    public boolean isDeployed(String deploymentName) throws IOException, org.wildfly.extras.creaper.core.online.operations.OperationException {
        Operations ops = getOperations();
        Address deploymentAddress = Address.deployment(deploymentName);

        if (!ops.exists(deploymentAddress)) {
            return false;
        }

        org.wildfly.extras.creaper.core.online.ModelNodeResult result = ops.readAttribute(deploymentAddress, "enabled");
        result.assertSuccess();
        return result.value().asBoolean();
    }

    /**
     * Reload the server.
     */
    public void reload() throws IOException, InterruptedException, TimeoutException {
        log.info("Reloading worker '{}'", name);
        Administration admin = getAdministration();
        admin.reload();
        log.info("Worker '{}' reloaded", name);
    }

    /**
     * Read a mod_cluster subsystem attribute.
     */
    public ModelNode readModClusterAttribute(String attributeName) throws IOException, org.wildfly.extras.creaper.core.online.operations.OperationException {
        Operations ops = getOperations();
        Address modclusterAddress = Address.subsystem("modcluster").and("proxy", "default");
        org.wildfly.extras.creaper.core.online.ModelNodeResult result = ops.readAttribute(modclusterAddress, attributeName);
        result.assertSuccess();
        return result.value();
    }

    /**
     * Write a mod_cluster subsystem attribute.
     */
    public void writeModClusterAttribute(String attributeName, Object value) throws IOException, org.wildfly.extras.creaper.core.online.operations.OperationException {
        Operations ops = getOperations();
        Address modclusterAddress = Address.subsystem("modcluster").and("proxy", "default");

        org.wildfly.extras.creaper.core.online.ModelNodeResult result;

        // Handle different value types
        if (value instanceof Boolean) {
            result = ops.writeAttribute(modclusterAddress, attributeName, (Boolean) value);
        } else if (value instanceof Integer) {
            result = ops.writeAttribute(modclusterAddress, attributeName, (Integer) value);
        } else if (value instanceof Long) {
            result = ops.writeAttribute(modclusterAddress, attributeName, (Long) value);
        } else if (value instanceof String) {
            result = ops.writeAttribute(modclusterAddress, attributeName, (String) value);
        } else if (value instanceof ModelNode) {
            result = ops.writeAttribute(modclusterAddress, attributeName, (ModelNode) value);
        } else {
            throw new IllegalArgumentException("Unsupported attribute value type: " + value.getClass());
        }

        result.assertSuccess();
        log.info("Set mod_cluster attribute '{}' to '{}' on worker '{}'", attributeName, value, name);
    }
}
