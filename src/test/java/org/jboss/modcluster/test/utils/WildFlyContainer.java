package org.jboss.modcluster.test.utils;

import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.wildfly.extras.creaper.core.ManagementClient;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.OnlineOptions;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.OperationException;
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
    public void configureStaticProxy() {
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

            ModelNodeResult writeResult =
                ops.writeAttribute(mcProxyAddress, "proxies", proxyList);
            writeResult.assertSuccess();

            // Step 3: Set listener to "default" for HTTP communication with Undertow balancers
            // (default listener attribute is "ajp" which is for Apache httpd)
            ModelNodeResult listenerResult =
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
    public void deployDemoApp() {
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
    public boolean isDeployed(String deploymentName) throws IOException, OperationException {
        Operations ops = getOperations();
        Address deploymentAddress = Address.deployment(deploymentName);

        if (!ops.exists(deploymentAddress)) {
            return false;
        }

        ModelNodeResult result = ops.readAttribute(deploymentAddress, "enabled");
        result.assertSuccess();
        return result.value().asBoolean();
    }

    /**
     * Reload the server configuration (preserves changes, lighter than full restart).
     */
    public void reload() throws Exception {
        log.info("Reloading worker '{}'", name);

        OnlineManagementClient client = getManagementClient();

        // Execute reload operation
        ModelNode reloadOp = new ModelNode();
        reloadOp.get("operation").set("reload");
        reloadOp.get("blocking").set(false); // Don't block waiting

        ModelNode result = client.execute(reloadOp);
        if (!"success".equals(result.get("outcome").asString())) {
            throw new RuntimeException("Reload failed: " + result.get("failure-description").asString());
        }

        // Close and nullify client as server is reloading
        client.close();
        managementClient = null;

        log.info("Reload initiated, waiting for server to come back up...");

        // Wait for server to come back up
        waitForManagementReady();

        // Reconfigure static proxy and redeploy demo after reload
        configureStaticProxy();
        deployDemoApp();

        log.info("Worker '{}' reloaded successfully", name);
    }

    /**
     * Read a mod_cluster subsystem attribute.
     */
    public ModelNode readModClusterAttribute(String attributeName) throws IOException, OperationException {
        Operations ops = getOperations();
        Address modclusterAddress = Address.subsystem("modcluster").and("proxy", "default");
        ModelNodeResult result = ops.readAttribute(modclusterAddress, attributeName);
        result.assertSuccess();
        return result.value();
    }

    /**
     * Write a mod_cluster subsystem attribute.
     */
    public void writeModClusterAttribute(String attributeName, Object value) throws IOException, OperationException {
        Operations ops = getOperations();
        Address modclusterAddress = Address.subsystem("modcluster").and("proxy", "default");

        ModelNodeResult result;

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

    /**
     * Deploy custom load metric module to WildFly.
     * Copies JAR and module.xml to the modules directory.
     */
    public void deployCustomLoadMetric() throws Exception {
        log.info("Deploying custom load metric to worker '{}'", name);

        // Copy JAR file
        File jarFile = new File("src/test/resources/custom-load-metric/target/custom-load-metric.jar");
        if (!jarFile.exists()) {
            throw new IllegalStateException("Custom load metric JAR not found. Run: mvn -f src/test/resources/custom-load-metric/pom.xml clean package");
        }

        // Copy module.xml
        File moduleXml = new File("src/test/resources/custom-load-metric/module.xml");
        if (!moduleXml.exists()) {
            throw new IllegalStateException("Custom load metric module.xml not found at: " + moduleXml.getAbsolutePath());
        }

        String modulePath = "/opt/wildfly/modules/org/jboss/modcluster/test/metric/main/";

        // Create module directory
        container.execInContainer("mkdir", "-p", modulePath);

        // Copy files to container
        container.copyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(jarFile.toPath()),
                modulePath + "custom-load-metric.jar"
        );

        container.copyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(moduleXml.toPath()),
                modulePath + "module.xml"
        );

        log.info("Custom load metric module deployed to worker '{}'", name);
    }

    /**
     * Configure custom load metric in mod_cluster subsystem.
     * Adds the custom load metric to the dynamic load provider.
     * The custom metric module must already be available (pre-baked in image or deployed).
     * Stops and restarts the mod_cluster proxy to force metric reload.
     *
     * @param loadFilePath Path to file containing load data
     * @param capacity Maximum load value for normalization
     * @param weight Weight of this metric in load calculation
     */
    public void configureCustomLoadMetric(String loadFilePath, int capacity, int weight) throws Exception {
        log.info("Configuring custom load metric on worker '{}' (file={}, capacity={}, weight={})",
                name, loadFilePath, capacity, weight);

        Operations ops = getOperations();

        // Build the custom-load-metric address
        Address metricAddress = Address.subsystem("modcluster")
                .and("proxy", "default")
                .and("load-provider", "dynamic")
                .and("custom-load-metric", "file-based");

        // Build properties for the custom load metric
        ModelNode properties = new ModelNode();
        properties.get("loadFile").set(loadFilePath);
        properties.get("parseExpression").set("^LOAD: ([0-9]+)$");

        // Add the custom load metric using Creaper Operations
        ModelNodeResult result = ops.add(metricAddress,
                org.wildfly.extras.creaper.core.online.operations.Values.empty()
                        .and("class", "org.jboss.modcluster.test.metric.FileBasedLoadMetric")
                        .and("module", "org.jboss.modcluster.test.metric")
                        .and("capacity", capacity)
                        .and("weight", weight)
                        .and("property", properties));

        result.assertSuccess();

        log.info("Custom load metric added to configuration, restarting server to load module...");

        getAdministration().restart();

        managementClient = null;

        log.info("Server restart initiated, waiting for server to come back up...");

        // Wait for server to restart and management interface to be ready
        waitForManagementReady();

        // Reconfigure static proxy and redeploy demo after restart
        configureStaticProxy();
        deployDemoApp();

        log.info("Custom load metric activated on worker '{}'", name);
    }

    /**
     * Restart the container (stop and start) to apply configuration changes.
     * Reconfigures static proxy, redeploys custom load metric module, and redeploys demo app after restart.
     */
    public void restart() throws Exception {
        log.info("Restarting worker '{}'", name);

        // Remember if custom load metric was deployed
        File customMetricJar = new File("src/test/resources/custom-load-metric/target/custom-load-metric.jar");
        boolean hasCustomMetric = customMetricJar.exists();

        // Close management client before stopping
        if (managementClient != null) {
            try {
                managementClient.close();
            } catch (IOException e) {
                log.warn("Error closing management client for restart", e);
            }
            managementClient = null;
        }

        // Stop and start container
        container.stop();
        container.start();

        log.info("Worker '{}' restarted, waiting for server startup...", name);

        // Wait for management interface to be ready (poll instead of sleep)
        waitForManagementReady();

        // Redeploy custom load metric if it was deployed before restart
        if (hasCustomMetric) {
            log.info("Redeploying custom load metric module after restart...");
            deployCustomLoadMetric();
        }

        // Reconfigure static proxy connection
        configureStaticProxy();

        // Redeploy demo application
        deployDemoApp();

        log.info("Worker '{}' restart complete", name);
    }

    /**
     * Wait for management interface to be ready by polling.
     */
    private void waitForManagementReady() throws Exception {
        int maxAttempts = 60;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                OnlineManagementClient client = getManagementClient();
                // Try a simple operation
                ModelNode result = client.execute(":read-attribute(name=server-state)");
                if (result.get("outcome").asString().equals("success")) {
                    log.info("Management interface ready for worker '{}'", name);
                    // Give it a bit more time to be fully stable
                    Thread.sleep(2000);
                    return;
                }
            } catch (Exception e) {
                // Not ready yet, wait and retry
            }
            Thread.sleep(1000);
        }
        throw new RuntimeException("Management interface not ready after " + maxAttempts + " seconds");
    }

    /**
     * Wait for worker to fully register with the balancer after startup.
     */
    public void waitForBalancerRegistration(String balancerUrl, int timeoutSeconds) throws Exception {
        log.info("Waiting for worker '{}' to register with balancer...", name);

        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try {
                // Try to make a request through the balancer
                String testUrl = balancerUrl;
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(testUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    // Read response to check which worker served it
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    // Check if any worker is serving (both should eventually register)
                    if (response.toString().contains("Served by")) {
                        log.info("Worker registered - balancer is routing traffic");
                        Thread.sleep(2000); // Extra time for full registration
                        return;
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                // Not ready yet, continue waiting
            }
            Thread.sleep(1000);
        }

        log.warn("Worker '{}' may not be fully registered after {} seconds", name, timeoutSeconds);
    }

    /**
     * Write load value to the container for custom load metric testing.
     *
     * @param loadValue The load value to write (will be normalized by capacity)
     */
    public void writeLoadValue(int loadValue) throws Exception {
        writeLoadValue(loadValue, "/tmp/modcluster-load.txt");
    }

    /**
     * Write load value to a specific file in the container.
     * Uses file copy with retry logic to handle transient SIGPIPE errors after container restart.
     *
     * @param loadValue The load value to write
     * @param filePath Path to the load file in the container
     */
    public void writeLoadValue(int loadValue, String filePath) throws Exception {
        log.info("Setting load value {} on worker '{}' (file: {})", loadValue, name, filePath);

        // Create temp file with load value
        java.io.File tempFile = java.io.File.createTempFile("modcluster-load-", ".txt");
        try {
            java.nio.file.Files.writeString(tempFile.toPath(), String.format("LOAD: %d%n", loadValue));

            // Retry copy operation to handle transient SIGPIPE errors
            int maxRetries = 5;
            Exception lastException = null;

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    container.copyFileToContainer(
                        org.testcontainers.utility.MountableFile.forHostPath(tempFile.toPath()),
                        filePath
                    );
                    log.debug("Load value {} written to {} on worker '{}' (attempt {})",
                        loadValue, filePath, name, attempt);
                    return; // Success
                } catch (Exception e) {
                    lastException = e;
                    if (e.getMessage() != null && e.getMessage().contains("SIGPIPE") && attempt < maxRetries) {
                        log.debug("SIGPIPE error on attempt {}, retrying after 2s...", attempt);
                        Thread.sleep(2000);
                    } else if (attempt < maxRetries) {
                        log.debug("Error on attempt {}, retrying: {}", attempt, e.getMessage());
                        Thread.sleep(1000);
                    }
                }
            }

            // All retries failed
            throw new RuntimeException("Failed to write load value after " + maxRetries + " attempts", lastException);
        } finally {
            tempFile.delete();
        }
    }

    /**
     * Wait for worker to be accessible via the balancer.
     * Polls the balancer URL until the worker responds or timeout is reached.
     *
     * @param balancerUrl The balancer URL to test
     * @param timeoutSeconds Maximum time to wait
     * @return true if worker became accessible, false if timeout
     */
    public boolean waitForRegistration(String balancerUrl, int timeoutSeconds) throws Exception {
        log.info("Waiting for worker '{}' to be accessible via balancer: {}", name, balancerUrl);

        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try {
                // Try to access via balancer
                var execResult = container.execInContainer(
                    "sh", "-c",
                    String.format("curl -s -o /dev/null -w '%%{http_code}' '%s' 2>/dev/null || echo 000", balancerUrl)
                );

                String httpCode = execResult.getStdout().trim();
                if ("200".equals(httpCode)) {
                    log.info("Worker '{}' is accessible via balancer", name);
                    return true;
                }

                Thread.sleep(1000);
            } catch (Exception e) {
                // Continue waiting
                Thread.sleep(1000);
            }
        }

        log.warn("Worker '{}' not accessible via balancer after {} seconds", name, timeoutSeconds);
        return false;
    }

    /**
     * Get the last N lines from the WildFly server log.
     *
     * @param lines Number of lines to retrieve
     * @return Server log content
     */
    public String getServerLog(int lines) throws Exception {
        var result = container.execInContainer(
            "sh", "-c",
            String.format("tail -%d /opt/wildfly/standalone/log/server.log 2>/dev/null || echo 'Log file not found'", lines)
        );
        return result.getStdout();
    }

    /**
     * Get the full server log.
     *
     * @return Complete server log content
     */
    public String getServerLog() throws Exception {
        var result = container.execInContainer(
            "cat", "/opt/wildfly/standalone/log/server.log"
        );
        return result.getStdout();
    }

    /**
     * Grep the server log for specific patterns.
     *
     * @param pattern Regex pattern to search for
     * @return Matching lines from the log
     */
    public String grepServerLog(String pattern) throws Exception {
        var result = container.execInContainer(
            "sh", "-c",
            String.format("grep -i '%s' /opt/wildfly/standalone/log/server.log || echo 'No matches found'", pattern)
        );
        return result.getStdout();
    }

    /**
     * Check if custom load metric module files exist in the container.
     *
     * @return true if module files are present
     */
    public boolean hasCustomLoadMetricModule() throws Exception {
        try {
            var jarCheck = container.execInContainer(
                "test", "-f", "/opt/wildfly/modules/org/jboss/modcluster/test/metric/main/custom-load-metric.jar"
            );
            var xmlCheck = container.execInContainer(
                "test", "-f", "/opt/wildfly/modules/org/jboss/modcluster/test/metric/main/module.xml"
            );
            return jarCheck.getExitCode() == 0 && xmlCheck.getExitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * List custom load metric module files.
     *
     * @return Directory listing or error message
     */
    public String listCustomLoadMetricModule() throws Exception {
        var result = container.execInContainer(
            "sh", "-c",
            "ls -la /opt/wildfly/modules/org/jboss/modcluster/test/metric/main/ 2>&1"
        );
        return result.getStdout();
    }
}
