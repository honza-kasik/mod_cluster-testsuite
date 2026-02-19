package org.jboss.modcluster.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.OperationException;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.commands.deployments.Deploy;
import org.wildfly.extras.creaper.commands.deployments.Undeploy;

import java.io.File;
import java.io.IOException;

/**
 * Manages application deployment lifecycle for WildFly containers.
 * Handles deployment, undeployment, and deployment status checks.
 */
public class WildFlyDeploymentManager {

    private static final Logger log = LoggerFactory.getLogger(WildFlyDeploymentManager.class);

    private final WildFlyContainer container;

    public WildFlyDeploymentManager(WildFlyContainer container) {
        this.container = container;
    }

    /**
     * Deploy an application to this worker using Creaper.
     *
     * @param deploymentFile The deployment file to deploy
     * @throws Exception if deployment fails
     */
    public void deploy(File deploymentFile) throws Exception {
        log.info("Deploying {} to worker '{}' using Creaper", deploymentFile.getName(), container.getName());

        OnlineManagementClient client = container.getManagementClient();

        // Deploy using Creaper deployment command
        client.apply(new Deploy.Builder(deploymentFile).build());

        log.info("Deployment {} succeeded on worker '{}'", deploymentFile.getName(), container.getName());
    }

    /**
     * Deploy an application using filesystem deployment (alternative method).
     *
     * @param deploymentFile The deployment file to deploy
     * @throws Exception if deployment fails
     */
    public void deployViaFilesystem(File deploymentFile) throws Exception {
        log.info("Deploying {} to worker '{}' via filesystem", deploymentFile.getName(), container.getName());

        container.getContainer().copyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(deploymentFile.toPath()),
                "/opt/wildfly/standalone/deployments/" + deploymentFile.getName()
        );

        // Wait for deployment (check for .deployed marker)
        String deploymentName = deploymentFile.getName();
        int maxWait = 30; // seconds
        for (int i = 0; i < maxWait; i++) {
            try {
                Container.ExecResult result = container.getContainer().execInContainer(
                        "sh", "-c",
                        "ls /opt/wildfly/standalone/deployments/" + deploymentName + ".deployed 2>/dev/null"
                );
                if (result.getExitCode() == 0) {
                    log.info("Deployment {} succeeded on worker '{}'", deploymentName, container.getName());
                    return;
                }
            } catch (Exception e) {
                // Ignore, keep waiting
            }
            Thread.sleep(1000);
        }

        log.warn("Deployment {} may not have completed on worker '{}' (timeout)", deploymentName, container.getName());
    }

    /**
     * Undeploy an application from this worker using Creaper.
     *
     * @param deploymentName The name of the deployment to undeploy
     * @throws Exception if undeployment fails
     */
    public void undeploy(String deploymentName) throws Exception {
        log.info("Undeploying {} from worker '{}'", deploymentName, container.getName());

        OnlineManagementClient client = container.getManagementClient();
        client.apply(new Undeploy.Builder(deploymentName).build());

        log.info("Undeployed {} from worker '{}'", deploymentName, container.getName());
    }

    /**
     * Check if a deployment exists and is enabled.
     *
     * @param deploymentName The name of the deployment to check
     * @return true if the deployment exists and is enabled, false otherwise
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public boolean isDeployed(String deploymentName) throws IOException, OperationException {
        Operations ops = container.getOperations();
        Address deploymentAddress = Address.deployment(deploymentName);

        if (!ops.exists(deploymentAddress)) {
            return false;
        }

        ModelNodeResult result = ops.readAttribute(deploymentAddress, "enabled");
        result.assertSuccess();
        return result.value().asBoolean();
    }

    /**
     * Deploy the demo application for testing.
     * Copies demo.war from resources and deploys it to the worker.
     */
    public void deployDemoApp() {
        try {
            // Copy demo.war from resources
            File demoWar = new File("src/test/resources/deployments/demo.war");
            if (demoWar.exists()) {
                log.info("Deploying demo application to worker '{}' using Creaper", container.getName());
                deploy(demoWar);

                // Wait for mod_cluster to register the context with the balancer
                log.debug("Waiting for context registration with mod_cluster...");
                Thread.sleep(2000);
            } else {
                log.warn("Demo application not found at: {}", demoWar.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("Failed to deploy demo application to worker '{}'", container.getName(), e);
        }
    }
}
