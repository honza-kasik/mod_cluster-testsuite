package org.jboss.modcluster.test.utils;

import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.wildfly.extras.creaper.core.ManagementClient;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.OnlineOptions;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.admin.Administration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

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
    private WildFlyDeploymentManager deploymentManager;
    private WildFlyModClusterManager modClusterManager;
    private WildFlyLoadMetricsManager loadMetricsManager;
    private WildFlyJGroupsManager jgroupsManager;

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
     * Includes optimized retry logic for transient Podman socket errors (SIGPIPE).
     */
    private void startFromPreBuiltImage(String imageName) {
        final int maxRetries = 5;
        Exception lastException = null;
        final java.util.Random random = new java.util.Random();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                container = new GenericContainer<>(imageName)
                        .withNetwork(balancer.getNetwork())
                        .withNetworkAliases(name)
                        .withExposedPorts(HTTP_PORT, HTTPS_PORT, MANAGEMENT_PORT)
                        .withEnv("JAVA_OPTS", "-Xms2048m -Xmx2048m")
                        .withCommand("/opt/wildfly/bin/standalone.sh",
                                    "-b", "0.0.0.0",
                                    "-bmanagement", "0.0.0.0",
                                    "-bprivate", "0.0.0.0",
                                    "-Djboss.node.name=" + name,
                                    "-Djboss.server.default.config=standalone-ha.xml",
                                    "-Djboss.modcluster.multicast.address=224.0.1.105",
                                    "-Djboss.modcluster.multicast.port=23364")
                        .waitingFor(Wait.forLogMessage(".*WFLYSRV0025.*", 1)
                                .withStartupTimeout(Duration.ofMinutes(5)))
                        .withLogConsumer(outputFrame ->
                                System.out.println("[" + name.toUpperCase() + "] " + outputFrame.getUtf8String().trim()));

                container.start();
                log.info("WildFly worker '{}' started{}", name, attempt > 1 ? " (attempt " + attempt + ")" : "");

                // Wait a bit for management interface to be fully ready
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Configure JGroups TCP for container-based clustering
                // (UDP multicast discovery does not work in Docker/Podman networks)
                jgroups().configureTcpDiscovery();
                reloadAndWait();

                // Configure static proxy connection
                modCluster().configureStaticProxy();

                // Deploy demo application automatically
                deployment().deployDemoApp();

                return; // Success - exit retry loop

            } catch (Exception e) {
                lastException = e;

                if (ContainerUtils.isTransientDockerError(e) && attempt < maxRetries) {
                    // Exponential backoff with jitter: 500ms, 1s, 1.5s + random(100-300ms)
                    final long baseDelay = attempt * 500L;
                    final long jitter = 100 + random.nextInt(200);
                    final long delayMs = baseDelay + jitter;

                    log.warn("Container start failed with transient error on attempt {}/{}, retrying after {}ms",
                             attempt, maxRetries, delayMs);
                    log.debug("Error details: {}", getRootCauseMessage(e));

                    // Clean up failed container reference
                    if (container != null) {
                        try {
                            container.close();
                        } catch (Exception cleanupEx) {
                            log.debug("Error during cleanup: {}", cleanupEx.getMessage());
                        }
                        container = null;
                    }

                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry backoff", ie);
                    }
                } else {
                    // Not a retryable error or max retries reached
                    break;
                }
            }
        }

        // All retries failed
        throw new RuntimeException("Failed to start WildFly worker '" + name + "' after " +
                                  maxRetries + " attempts", lastException);
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

        // Stop and remove container
        if (container != null) {
            try {
                if (container.isRunning()) {
                    container.stop();
                    log.info("WildFly worker '{}' stopped", name);
                }

                // Explicitly remove container to reduce Ryuk cleanup backlog
                String containerId = container.getContainerId();
                if (containerId != null) {
                    container.getDockerClient()
                        .removeContainerCmd(containerId)
                        .withForce(true)
                        .exec();
                    log.debug("WildFly worker '{}' container removed", name);
                }
            } catch (Exception e) {
                log.debug("Ignoring error while stopping/removing worker '{}': {}", name, e.getMessage());
            }
            // Clear references
            container = null;
            deploymentManager = null;
            modClusterManager = null;
            loadMetricsManager = null;
            jgroupsManager = null;
        }
    }

    /**
     * Hard kill the worker (simulates crash/SIGKILL).
     * Kills the container immediately without graceful shutdown.
     */
    public void kill() {
        // Close management client
        if (managementClient != null) {
            try {
                managementClient.close();
            } catch (IOException e) {
                log.warn("Error closing management client for worker '{}'", name, e);
            }
            managementClient = null;
        }

        // Kill, stop, and remove container
        if (container != null) {
            try {
                if (container.isRunning()) {
                    String containerId = container.getContainerId();

                    // SIGKILL the container
                    container.getDockerClient()
                        .killContainerCmd(containerId)
                        .withSignal("KILL")
                        .exec();
                    log.info("WildFly worker '{}' killed (hard stop)", name);

                    // Stop to trigger Testcontainers cleanup
                    container.stop();

                    // Explicitly remove container
                    container.getDockerClient()
                        .removeContainerCmd(containerId)
                        .withForce(true)
                        .exec();
                    log.debug("WildFly worker '{}' container removed after kill", name);
                }
            } catch (Exception e) {
                log.debug("Ignoring error while killing/removing worker '{}': {}", name, e.getMessage());
            }
            // Clear all references
            container = null;
            deploymentManager = null;
            modClusterManager = null;
            loadMetricsManager = null;
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
     * Get deployment manager for this worker.
     * Provides access to deployment operations (deploy, undeploy, status checks).
     *
     * @return cached deployment manager instance
     */
    public WildFlyDeploymentManager deployment() {
        if (deploymentManager == null) {
            deploymentManager = new WildFlyDeploymentManager(this);
        }
        return deploymentManager;
    }

    /**
     * Get mod_cluster configuration manager for this worker.
     * Provides access to mod_cluster subsystem operations (proxy config, attributes).
     *
     * @return cached mod_cluster manager instance
     */
    public WildFlyModClusterManager modCluster() {
        if (modClusterManager == null) {
            modClusterManager = new WildFlyModClusterManager(this);
        }
        return modClusterManager;
    }

    /**
     * Get load metrics manager for this worker.
     * Provides access to load metric configuration (custom metrics, load values).
     *
     * @return cached load metrics manager instance
     */
    public WildFlyLoadMetricsManager loadMetrics() {
        if (loadMetricsManager == null) {
            loadMetricsManager = new WildFlyLoadMetricsManager(this);
        }
        return loadMetricsManager;
    }

    /**
     * Get JGroups manager for this worker.
     * Provides access to JGroups subsystem configuration (TCP/TCPPING discovery).
     *
     * @return cached JGroups manager instance
     */
    public WildFlyJGroupsManager jgroups() {
        if (jgroupsManager == null) {
            jgroupsManager = new WildFlyJGroupsManager(this);
        }
        return jgroupsManager;
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
        Container.ExecResult execResult = container.execInContainer(
                "sh", "-c",
                "jboss-cli.sh --connect --controller=localhost:9990 --command='" + command + "'"
        );

        if (execResult.getExitCode() != 0) {
            throw new RuntimeException("CLI command failed: " + execResult.getStderr());
        }

        return execResult.getStdout();
    }


    /**
     * Reload the server configuration and wait for management to be ready.
     * Does not reconfigure proxy or redeploy applications.
     */
    private void reloadAndWait() throws Exception {
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

        log.info("Worker '{}' reloaded successfully", name);
    }

    /**
     * Reload the server configuration and wait for management to be ready.
     * Does not reconfigure static proxy or redeploy applications.
     * Use this when the management model already contains the desired configuration
     * (e.g., MCMP-over-SSL settings that must take effect via reload).
     */
    public void reloadServer() throws Exception {
        reloadAndWait();
    }

    /**
     * Reload the server configuration (preserves changes, lighter than full restart).
     * Reconfigures static proxy and redeploys demo application after reload.
     */
    public void reload() throws Exception {
        reloadAndWait();

        // Reconfigure static proxy and redeploy demo after reload
        modCluster().configureStaticProxy();
        deployment().deployDemoApp();
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
                Container.ExecResult execResult = container.execInContainer(
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
        Container.ExecResult result = container.execInContainer(
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
        Container.ExecResult result = container.execInContainer(
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
        Container.ExecResult result = container.execInContainer(
            "sh", "-c",
            String.format("grep -i '%s' /opt/wildfly/standalone/log/server.log || echo 'No matches found'", pattern)
        );
        return result.getStdout();
    }

    /**
     * Get the root cause message from exception chain.
     *
     * @param throwable Exception to traverse
     * @return Root cause message or top-level message if no cause
     */
    private String getRootCauseMessage(Throwable throwable) {
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        return rootCause.getMessage() != null ? rootCause.getMessage() : rootCause.toString();
    }

}
