package org.jboss.modcluster.test.utils;

import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.ReadResourceOption;
import org.wildfly.extras.creaper.core.online.operations.Values;

import java.io.File;

/**
 * Manages load metrics configuration and management for WildFly containers.
 * Handles custom load metric deployment, configuration, and load value updates.
 */
public class WildFlyLoadMetricsManager {

    private static final Logger log = LoggerFactory.getLogger(WildFlyLoadMetricsManager.class);

    private final WildFlyContainer container;

    public WildFlyLoadMetricsManager(WildFlyContainer container) {
        this.container = container;
    }

    /**
     * Deploy custom load metric module to WildFly.
     * Copies JAR and module.xml to the modules directory.
     *
     * @throws Exception if deployment fails
     */
    public void deployCustomLoadMetric() throws Exception {
        log.info("Deploying custom load metric to worker '{}'", container.getName());

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
        container.getContainer().execInContainer("mkdir", "-p", modulePath);

        // Copy files to container
        container.getContainer().copyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(jarFile.toPath()),
                modulePath + "custom-load-metric.jar"
        );

        container.getContainer().copyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(moduleXml.toPath()),
                modulePath + "module.xml"
        );

        log.info("Custom load metric module deployed to worker '{}'", container.getName());
    }

    /**
     * Configure which built-in load metric to use.
     * By default, WildFly has "cpu" metric. This only changes config if needed.
     *
     * @param metricName Name of the metric to use ("cpu" or "heap")
     * @throws Exception if configuration fails
     */
    public void configureLoadMetric(String metricName) throws Exception {
        log.info("Configuring worker '{}' to use '{}' load metric", container.getName(), metricName);

        Operations ops = container.getOperations();
        Address dynamicProviderAddr = Address.subsystem("modcluster").and("proxy", "default").and("load-provider", "dynamic");

        if (metricName.equals("cpu")) {
            // CPU is already the default, nothing to do
            log.info("CPU metric is already configured by default");
            return;
        }

        // For non-CPU metrics: remove CPU and add the desired metric
        Address cpuMetricAddr = dynamicProviderAddr.and("load-metric", "cpu");
        if (ops.exists(cpuMetricAddr)) {
            ops.remove(cpuMetricAddr);
            log.info("Removed default CPU metric");
        }

        Address metricAddr = dynamicProviderAddr.and("load-metric", metricName);
        // Add metric with required attributes: type and weight (matching noe-tests)
        ops.add(metricAddr, Values.of("type", metricName).and("weight", 1))
                .assertSuccess("Failed to add load metric: " + metricName);
        log.info("Added load metric: {} with type={} and weight=1", metricName, metricName);

        // Reload to apply changes
        log.info("Reloading server to apply load metric configuration...");
        container.getAdministration().reload();

        // Wait for reload
        waitForManagementReady();

        log.info("Worker '{}' configured to use '{}' metric", container.getName(), metricName);
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
     * @throws Exception if configuration fails
     */
    public void configureCustomLoadMetric(String loadFilePath, int capacity, int weight) throws Exception {
        log.info("Configuring custom load metric on worker '{}' (file={}, capacity={}, weight={})",
                container.getName(), loadFilePath, capacity, weight);

        Operations ops = container.getOperations();

        Address dynamicProviderAddr = Address.subsystem("modcluster").and("proxy", "default").and("load-provider", "dynamic");

        // FIRST: Set history=0 and decay=0 for immediate load reflection (BEFORE adding custom metric)
        log.info("Setting history=0 and decay=0 for immediate load reflection");
        ops.writeAttribute(dynamicProviderAddr, "history", 0).assertSuccess();
        ops.writeAttribute(dynamicProviderAddr, "decay", 0).assertSuccess();

        // SECOND: Remove CPU metric (following noe-tests approach: only custom metric, no built-in metrics)
        log.info("Removing all built-in load metrics to use only custom metric");
        Address cpuMetricAddr = dynamicProviderAddr.and("load-metric", "cpu");
        ops.remove(cpuMetricAddr).assertSuccess();

        // THIRD: Add the custom load metric
        Address metricAddress = dynamicProviderAddr.and("custom-load-metric", "file-based");

        // Build properties for the custom load metric (lowercase names to match setters)
        ModelNode properties = new ModelNode();
        properties.get("loadfile").set(loadFilePath);
        properties.get("parseexpression").set("^LOAD: ([0-9]+)$");

        // Add the custom load metric using Creaper Operations
        ModelNodeResult result = ops.add(metricAddress,
                Values.empty()
                        .and("class", "org.jboss.modcluster.test.metric.FileBasedLoadMetric")
                        .and("module", "org.jboss.modcluster.test.metric")
                        .and("capacity", capacity)
                        .and("weight", weight)
                        .and("property", properties));
        result.assertSuccess();

        ops.removeIfExists(Address.subsystem("modcluster").and("proxy", "default").and("load-provider", "simple"));


        log.info("Custom load metric added to configuration, restarting server to load module...");

        container.getAdministration().restart();

        log.info("Server restart initiated, waiting for server to come back up...");

        // Wait for server to restart and management interface to be ready
        waitForManagementReady();

        // Verify final configuration from management model
        ops = container.getOperations();
        ModelNodeResult finalConfig = ops.readResource(
            Address.subsystem("modcluster").and("proxy", "default").and("load-provider", "dynamic"),
            ReadResourceOption.INCLUDE_RUNTIME, ReadResourceOption.RECURSIVE);
        log.info("Final load-provider configuration after restart (from management): {}", finalConfig.value().toJSONString(true));

        // Also read the actual XML configuration file to see what's persisted
        try {
            Container.ExecResult xmlResult = container.getContainer().execInContainer(
                "cat", "/opt/wildfly/standalone/configuration/standalone-ha.xml"
            );

            // Extract just the mod_cluster subsystem section
            String fullXml = xmlResult.getStdout();
            int modclusterStart = fullXml.indexOf("<subsystem xmlns=\"urn:jboss:domain:modcluster:");
            if (modclusterStart != -1) {
                int modclusterEnd = fullXml.indexOf("</subsystem>", modclusterStart);
                if (modclusterEnd != -1) {
                    String modclusterXml = fullXml.substring(modclusterStart, modclusterEnd + 12);
                    log.info("Mod_cluster subsystem in standalone-ha.xml:\n{}", modclusterXml);
                }
            }
        } catch (Exception e) {
            log.warn("Could not read standalone-ha.xml: {}", e.getMessage());
        }

        log.info("Custom load metric activated on worker '{}'", container.getName());
    }

    /**
     * Wait for management interface to be ready by polling.
     *
     * @throws Exception if management interface doesn't become ready within timeout
     */
    private void waitForManagementReady() throws Exception {
        int maxAttempts = 60;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                OnlineManagementClient client = container.getManagementClient();
                // Try a simple operation
                ModelNode result = client.execute(":read-attribute(name=server-state)");
                if (result.get("outcome").asString().equals("success")) {
                    log.info("Management interface ready for worker '{}'", container.getName());
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
     * Write load value to a specific file in the container.
     * Uses file copy with retry logic to handle transient SIGPIPE errors after container restart.
     *
     * @param loadValue The load value to write
     * @param filePath Path to the load file in the container
     * @throws Exception if writing the load value fails
     */
    public void writeLoadValue(int loadValue, String filePath) throws Exception {
        log.info("Setting load value {} on worker '{}' (file: {})", loadValue, container.getName(), filePath);

        // Create temp file with load value
        java.io.File tempFile = java.io.File.createTempFile("modcluster-load-", ".txt");
        try {
            java.nio.file.Files.writeString(tempFile.toPath(), String.format("LOAD: %d%n", loadValue));

            // Retry copy operation to handle transient SIGPIPE errors
            int maxRetries = 5;
            Exception lastException = null;

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    container.getContainer().copyFileToContainer(
                        org.testcontainers.utility.MountableFile.forHostPath(tempFile.toPath()),
                        filePath
                    );
                    log.debug("Load value {} written to {} on worker '{}' (attempt {})",
                        loadValue, filePath, container.getName(), attempt);
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
     * Check if custom load metric module files exist in the container.
     *
     * @return true if module files are present
     * @throws Exception if check fails
     */
    public boolean hasCustomLoadMetricModule() throws Exception {
        try {
            Container.ExecResult jarCheck = container.getContainer().execInContainer(
                "test", "-f", "/opt/wildfly/modules/org/jboss/modcluster/test/metric/main/custom-load-metric.jar"
            );
            Container.ExecResult xmlCheck = container.getContainer().execInContainer(
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
     * @throws Exception if listing fails
     */
    public String listCustomLoadMetricModule() throws Exception {
        Container.ExecResult result = container.getContainer().execInContainer(
            "sh", "-c",
            "ls -la /opt/wildfly/modules/org/jboss/modcluster/test/metric/main/ 2>&1"
        );
        return result.getStdout();
    }
}
