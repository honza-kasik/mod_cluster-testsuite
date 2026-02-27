package org.jboss.modcluster.test.utils;

import org.jboss.modcluster.test.base.BalancerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.wildfly.extras.creaper.core.ManagementClient;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.OnlineOptions;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;
import org.wildfly.extras.creaper.core.online.operations.admin.Administration;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

/**
 * Container wrapper for load balancers (Undertow or httpd with mod_cluster).
 */
public abstract class BalancerContainer {

    private static final Logger log = LoggerFactory.getLogger(BalancerContainer.class);

    protected GenericContainer<?> container;
    protected Network network;
    protected BalancerType type;
    protected boolean ownsNetwork = true;

    protected static final int HTTP_PORT = 8080;
    protected static final int HTTPS_PORT = 8443;
    protected static final int MCMP_PORT = 6666;
    protected static final int MANAGEMENT_PORT = 9990;

    public static BalancerContainer create(BalancerType type) {
        switch (type) {
            case UNDERTOW:
                return new UndertowBalancerContainer();
            case HTTPD:
                return new HttpdBalancerContainer();
            default:
                throw new IllegalArgumentException("Unknown balancer type: " + type);
        }
    }

    public abstract void start();

    /**
     * Start the balancer on an existing network with a custom alias.
     * Used when multiple balancers share the same Docker network.
     *
     * @param network existing network to attach to
     * @param networkAlias alias for this balancer on the network
     */
    public abstract void start(Network network, String networkAlias);

    public void stop() {
        // Stop and remove container first
        if (container != null) {
            try {
                if (container.isRunning()) {
                    container.stop();
                    log.debug("Balancer container stopped");
                }

                // Explicitly remove container
                String containerId = container.getContainerId();
                if (containerId != null) {
                    container.getDockerClient()
                        .removeContainerCmd(containerId)
                        .withForce(true)
                        .exec();
                    log.debug("Balancer container removed");
                }
            } catch (Exception e) {
                log.debug("Ignoring error stopping/removing balancer container: {}", e.getMessage());
            }
        }

        // Give more time for container removal to complete before network cleanup
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Close network to free resources (only if this balancer owns the network)
        if (network != null && ownsNetwork) {
            try {
                network.close();
                log.debug("Network closed");
            } catch (Exception e) {
                log.debug("Ignoring error closing network: {}", e.getMessage());
            }
        }
    }

    public String getHttpUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(HTTP_PORT);
    }

    public String getHttpsUrl() {
        return "https://" + container.getHost() + ":" + container.getMappedPort(HTTPS_PORT);
    }

    public String getMcmpUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(MCMP_PORT);
    }

    public String getInternalHttpUrl() {
        return "http://" + container.getContainerInfo().getConfig().getHostName() + ":" + HTTP_PORT;
    }

    public Network getNetwork() {
        return network;
    }

    public GenericContainer<?> getContainer() {
        return container;
    }

    public BalancerType getType() {
        return type;
    }

    /**
     * Get worker/node information from the balancer.
     * Returns a map of worker names to their runtime information including load.
     */
    public abstract java.util.Map<String, org.jboss.dmr.ModelNode> getWorkerInfo() throws Exception;

    /**
     * Get the list of balancer group names registered on this balancer.
     * Each group corresponds to a distinct load-balancing pool of workers.
     *
     * @return list of balancer names (e.g., ["mycluster", "balancerXXX1"])
     * @throws Exception if the query fails
     */
    public abstract List<String> getBalancerNames() throws Exception;

    /**
     * Disable a node on this balancer via the mod_cluster filter management interface.
     * The node will not receive new requests but will continue serving existing sessions.
     *
     * @param nodeName the name of the node to disable (e.g., "worker1")
     * @throws Exception if the operation fails
     */
    public abstract void disableNode(String nodeName) throws Exception;

    /**
     * Stop a node on this balancer via the mod_cluster filter management interface.
     * The node will immediately stop receiving all requests.
     *
     * @param nodeName the name of the node to stop (e.g., "worker1")
     * @throws Exception if the operation fails
     */
    public abstract void stopNode(String nodeName) throws Exception;

    /**
     * Enable a previously disabled/stopped node on this balancer.
     *
     * @param nodeName the name of the node to enable (e.g., "worker1")
     * @throws Exception if the operation fails
     */
    public abstract void enableNode(String nodeName) throws Exception;

    /**
     * Disable a load-balancing group on this balancer.
     * All nodes in the group will not receive new requests but continue serving existing sessions.
     *
     * @param groupName the name of the load-balancing group to disable
     * @throws Exception if the operation fails
     */
    public abstract void disableLoadBalancingGroup(String groupName) throws Exception;

    /**
     * Stop a load-balancing group on this balancer.
     * All nodes in the group will immediately stop receiving all requests.
     *
     * @param groupName the name of the load-balancing group to stop
     * @throws Exception if the operation fails
     */
    public abstract void stopLoadBalancingGroup(String groupName) throws Exception;

    /**
     * Enable a previously disabled/stopped load-balancing group on this balancer.
     *
     * @param groupName the name of the load-balancing group to enable
     * @throws Exception if the operation fails
     */
    public abstract void enableLoadBalancingGroup(String groupName) throws Exception;

    /**
     * Get the context status for a specific node and context on this balancer.
     *
     * @param nodeName the name of the node (e.g., "worker1")
     * @param contextPath the context path (e.g., "/demo")
     * @return the context status string (e.g., "ENABLED", "DISABLED", "STOPPED")
     * @throws Exception if the query fails
     */
    public abstract String getContextStatus(String nodeName, String contextPath) throws Exception;

    /**
     * Get all registered context paths for a specific node on this balancer.
     * Returns the list of context paths (e.g., ["/demo", "/simplecontext-111"]) that
     * the balancer knows about for the given node.
     *
     * @param nodeName the name of the node (e.g., "worker1")
     * @return list of registered context paths, or empty list if node not found
     * @throws Exception if the query fails
     */
    public abstract List<String> getRegisteredContexts(String nodeName) throws Exception;

    /**
     * Disable a specific context on a node via the balancer management interface.
     * The context will not receive new requests but existing sessions continue.
     *
     * @param nodeName the name of the node (e.g., "worker1")
     * @param contextPath the context path (e.g., "/demo" or "demo")
     * @throws Exception if the operation fails
     */
    public abstract void disableContext(String nodeName, String contextPath) throws Exception;

    /**
     * Stop a specific context on a node via the balancer management interface.
     * The context will immediately stop receiving all requests.
     *
     * @param nodeName the name of the node (e.g., "worker1")
     * @param contextPath the context path (e.g., "/demo" or "demo")
     * @throws Exception if the operation fails
     */
    public abstract void stopContext(String nodeName, String contextPath) throws Exception;

    /**
     * Enable a previously disabled/stopped context on a node via the balancer management interface.
     *
     * @param nodeName the name of the node (e.g., "worker1")
     * @param contextPath the context path (e.g., "/demo" or "demo")
     * @throws Exception if the operation fails
     */
    public abstract void enableContext(String nodeName, String contextPath) throws Exception;

    /**
     * Set the max-retries attribute on the mod_cluster filter.
     * Controls how many times the balancer retries a failed request.
     * Undertow-specific setting.
     *
     * @param maxRetries the maximum number of retries
     * @throws Exception if the operation fails
     */
    public abstract void setMaxRetries(int maxRetries) throws Exception;

    /**
     * Reload the balancer server to apply configuration changes.
     * Should only be called when no workers are connected (e.g., during initial setup),
     * otherwise worker MCMP connections will be disrupted.
     *
     * @throws Exception if the reload fails
     */
    public abstract void reload() throws Exception;

    /**
     * Undertow-based mod_cluster balancer.
     * Uses the same WildFly/EAP ZIP as workers, but configured as a load balancer.
     */
    static class UndertowBalancerContainer extends BalancerContainer {

        @Override
        public void start() {
            type = BalancerType.UNDERTOW;
            network = Network.newNetwork();
            ownsNetwork = true;

            java.nio.file.Path zipPath = getWildFlyZipPath();

            if (zipPath != null && zipPath.toFile().exists()) {
                log.info("Building Undertow balancer from ZIP: {}", zipPath);
                startFromZip(zipPath, "balancer");
            } else {
                log.info("No ZIP provided, using pre-built Undertow balancer image");
                startFromImage("balancer");
            }
        }

        @Override
        public void start(Network network, String networkAlias) {
            type = BalancerType.UNDERTOW;
            this.network = network;
            ownsNetwork = false;

            java.nio.file.Path zipPath = getWildFlyZipPath();

            if (zipPath != null && zipPath.toFile().exists()) {
                log.info("Building Undertow balancer from ZIP: {}", zipPath);
                startFromZip(zipPath, networkAlias);
            } else {
                log.info("No ZIP provided, using pre-built Undertow balancer image");
                startFromImage(networkAlias);
            }
        }

        private void startFromZip(Path zipPath) {
            startFromZip(zipPath, "balancer");
        }

        private void startFromZip(Path zipPath, String networkAlias) {
            String zipFileName = zipPath.getFileName().toString();
            String javaVersion = getRequiredJavaVersion(zipFileName);

            // Generate consistent image tag (same as workers, image is reused!)
            String imageTag = ImageBuilder.generateImageTag(zipFileName, javaVersion);

            // Check if image already exists (might have been built by worker)
            if (!ImageBuilder.imageExists(imageTag)) {
                log.info("Building Undertow balancer from ZIP: {} (this may take a few minutes on first run)", zipFileName);
                ImageBuilder.buildImageFromZip(zipPath, javaVersion, imageTag);
            } else {
                log.info("Using existing image: {}", imageTag);
            }

            // Start with balancer configuration in --admin-only mode (like noe-tests)
            // Use standalone.xml (NOT standalone-ha.xml) - we'll configure mod_cluster filter
            container = new GenericContainer<>(imageTag)
                    .withNetwork(network)
                    .withNetworkAliases(networkAlias)
                    .withExposedPorts(HTTP_PORT, HTTPS_PORT, MANAGEMENT_PORT)
                    .withCommand("/opt/wildfly/bin/standalone.sh",
                                "-Djboss.node.name=" + networkAlias,
                                "-bmanagement", "0.0.0.0",
                                "--admin-only")
                    .waitingFor(Wait.forLogMessage(".*WFLYSRV0025.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(5)))
                    .withLogConsumer(outputFrame ->
                            log.debug("[UNDERTOW-BALANCER-{}] {}", networkAlias.toUpperCase(),
                                    outputFrame.getUtf8String().trim()));

            container.start();
            log.info("Undertow balancer '{}' started in admin-only mode on network: {}", networkAlias, network.getId());

            // Configure the balancer to act as a balancer (not a worker) - all changes in admin-only mode
            configureAsBalancer();
        }

        /**
         * Configure this WildFly instance to act as a mod_cluster balancer.
         * Uses Creaper Operations API following the order from noe-tests CLILib.
         * Server must be started in --admin-only mode to avoid reload-required state.
         */
        private void configureAsBalancer() {
            try {
                // Wait for management interface
                Thread.sleep(5000);

                OnlineManagementClient client =
                    ManagementClient.online(
                        OnlineOptions.standalone()
                            .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                            .auth("admin", "admin")
                            .build()
                    );

                Operations ops =
                    new Operations(client);

                log.info("Configuring Undertow mod_cluster filter on balancer (admin-only mode)");

                // Step 0: Get container's internal IP and configure public interface to use it
                String containerIp = container.getContainerInfo().getNetworkSettings().getNetworks()
                    .values().iterator().next().getIpAddress();
                log.info("Container IP: {}", containerIp);

                Address publicInterfaceAddr =
                    Address.of("interface", "public");

                // Undefine any-address first, then set inet-address
                ops.undefineAttribute(publicInterfaceAddr, "any-address");
                ops.writeAttribute(publicInterfaceAddr, "inet-address", containerIp)
                    .assertSuccess("Failed to configure public interface");
                log.info("Public interface configured to: {}", containerIp);

                // Step 1: Create multicast socket binding for advertisement
                Address multicastAddr =
                    Address
                        .of("socket-binding-group", "standard-sockets")
                        .and("socket-binding", "modcluster");

                ops.add(multicastAddr,
                    Values.of("port", 0)
                        .and("multicast-address", "224.0.1.105")
                        .and("multicast-port", 23364))
                    .assertSuccess("Failed to add multicast socket binding");
                log.info("Multicast socket binding created");

                // Step 2: Add mod_cluster filter to undertow using standard HTTP socket
                Address filterAddr =
                    Address.subsystem("undertow")
                        .and("configuration", "filter")
                        .and("mod-cluster", "modcluster");

                ops.add(filterAddr,
                    Values.of("management-socket-binding", "http")
                        .and("advertise-socket-binding", "modcluster")
                        .and("health-check-interval", 5)  // Check worker health every 5 seconds
                        .and("broken-node-timeout", 10)   // Mark as down after 10 seconds of no response
                        .and("failover-strategy", "LOAD_BALANCED"))  // Failover to least loaded node
                    .assertSuccess("Failed to add mod_cluster filter");
                log.info("Mod_cluster filter created with health checks and failover enabled");

                // Step 3: Add filter-ref to default-host (following CLILib order)
                Address filterRefAddr =
                    Address.subsystem("undertow")
                        .and("server", "default-server")
                        .and("host", "default-host")
                        .and("filter-ref", "modcluster");

                ops.add(filterRefAddr)
                    .assertSuccess("Failed to add filter-ref");
                log.info("Filter-ref added to default-host");

                // Step 4: Reload from admin-only mode to normal mode (like noe-tests stop/start)
                log.info("Reloading server to transition from admin-only to normal mode");
                new Administration(client).reload();

                client.close();

                log.info("Undertow balancer configured successfully. MCMP on HTTP socket binding (port {})", HTTP_PORT);

            } catch (Exception e) {
                log.error("Failed to configure balancer", e);
                throw new RuntimeException("Balancer configuration failed", e);
            }
        }

        @Override
        public java.util.Map<String, org.jboss.dmr.ModelNode> getWorkerInfo() throws Exception {
            java.util.Map<String, org.jboss.dmr.ModelNode> workerInfo = new java.util.HashMap<>();

            OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .build()
            );

            Operations ops = new Operations(client);

            // Address to mod_cluster filter
            Address filterAddr = Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

            // Get list of balancers
            List<String> balancers = ops.readChildrenNames(filterAddr, "balancer").stringListValue();
            log.debug("Balancers: {}", balancers);

            // For each balancer, get its nodes (workers)
            for (String balancerName : balancers) {
                Address balancerAddr = filterAddr.and("balancer", balancerName);
                List<String> nodes = ops.readChildrenNames(balancerAddr, "node").stringListValue();
                log.debug("Balancer '{}' has nodes: {}", balancerName, nodes);

                // For each node, read its runtime info
                for (String nodeName : nodes) {
                    Address nodeAddr = balancerAddr.and("node", nodeName);
                    org.wildfly.extras.creaper.core.online.ModelNodeResult result =
                        ops.readResource(nodeAddr, org.wildfly.extras.creaper.core.online.operations.ReadResourceOption.INCLUDE_RUNTIME);

                    if (result.isSuccess()) {
                        org.jboss.dmr.ModelNode nodeInfo = result.value();
                        workerInfo.put(nodeName, nodeInfo);
                        log.debug("Node '{}' info: {}", nodeName, nodeInfo.toJSONString(false));
                    }
                }
            }

            client.close();
            return workerInfo;
        }

        @Override
        public List<String> getBalancerNames() throws Exception {
            OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .build()
            );

            Operations ops = new Operations(client);

            Address filterAddr = Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

            List<String> balancers = ops.readChildrenNames(filterAddr, "balancer").stringListValue();
            log.debug("Balancer names: {}", balancers);

            client.close();
            return balancers;
        }

        @Override
        public void disableNode(String nodeName) throws Exception {
            invokeNodeOperation(nodeName, "disable");
        }

        @Override
        public void stopNode(String nodeName) throws Exception {
            invokeNodeOperation(nodeName, "stop");
        }

        @Override
        public void enableNode(String nodeName) throws Exception {
            invokeNodeOperation(nodeName, "enable");
        }

        @Override
        public void disableLoadBalancingGroup(String groupName) throws Exception {
            invokeGroupOperation(groupName, "disable");
        }

        @Override
        public void stopLoadBalancingGroup(String groupName) throws Exception {
            invokeGroupOperation(groupName, "stop");
        }

        @Override
        public void enableLoadBalancingGroup(String groupName) throws Exception {
            invokeGroupOperation(groupName, "enable");
        }

        @Override
        public String getContextStatus(String nodeName, String contextPath) throws Exception {
            OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .build()
            );

            Operations ops = new Operations(client);

            Address filterAddr = Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

            List<String> balancers = ops.readChildrenNames(filterAddr, "balancer").stringListValue();

            for (String balancerName : balancers) {
                Address balancerAddr = filterAddr.and("balancer", balancerName);
                List<String> nodes = ops.readChildrenNames(balancerAddr, "node").stringListValue();

                for (String node : nodes) {
                    if (node.equals(nodeName)) {
                        Address nodeAddr = balancerAddr.and("node", node);
                        List<String> contexts = ops.readChildrenNames(nodeAddr, "context").stringListValue();

                        String normalizedPath = contextPath.startsWith("/") ? contextPath : "/" + contextPath;

                        for (String ctx : contexts) {
                            if (ctx.equals(normalizedPath)) {
                                Address contextAddr = nodeAddr.and("context", ctx);
                                org.wildfly.extras.creaper.core.online.ModelNodeResult result =
                                    ops.readAttribute(contextAddr, "status");
                                client.close();
                                return result.stringValue();
                            }
                        }
                    }
                }
            }

            client.close();
            return null;
        }

        @Override
        public List<String> getRegisteredContexts(String nodeName) throws Exception {
            java.util.List<String> result = new java.util.ArrayList<>();

            OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .build()
            );

            Operations ops = new Operations(client);

            Address filterAddr = Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

            List<String> balancers = ops.readChildrenNames(filterAddr, "balancer").stringListValue();

            for (String balancerName : balancers) {
                Address balancerAddr = filterAddr.and("balancer", balancerName);
                List<String> nodes = ops.readChildrenNames(balancerAddr, "node").stringListValue();

                if (nodes.contains(nodeName)) {
                    Address nodeAddr = balancerAddr.and("node", nodeName);
                    List<String> contexts = ops.readChildrenNames(nodeAddr, "context").stringListValue();
                    result.addAll(contexts);
                }
            }

            client.close();
            return result;
        }

        @Override
        public void disableContext(String nodeName, String contextPath) throws Exception {
            invokeContextOperation(nodeName, contextPath, "disable");
        }

        @Override
        public void stopContext(String nodeName, String contextPath) throws Exception {
            invokeContextOperation(nodeName, contextPath, "stop");
        }

        @Override
        public void enableContext(String nodeName, String contextPath) throws Exception {
            invokeContextOperation(nodeName, contextPath, "enable");
        }

        /**
         * Invoke an operation on a specific context of a node in the mod_cluster filter.
         * Finds the context across all balancers and invokes the specified operation.
         */
        private void invokeContextOperation(String nodeName, String contextPath, String operation) throws Exception {
            OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .build()
            );

            Operations ops = new Operations(client);

            Address filterAddr = Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

            String normalizedPath = contextPath.startsWith("/") ? contextPath : "/" + contextPath;

            List<String> balancers = ops.readChildrenNames(filterAddr, "balancer").stringListValue();

            boolean found = false;
            for (String balancerName : balancers) {
                Address balancerAddr = filterAddr.and("balancer", balancerName);
                List<String> nodes = ops.readChildrenNames(balancerAddr, "node").stringListValue();

                if (nodes.contains(nodeName)) {
                    Address nodeAddr = balancerAddr.and("node", nodeName);
                    List<String> contexts = ops.readChildrenNames(nodeAddr, "context").stringListValue();

                    for (String ctx : contexts) {
                        if (ctx.equals(normalizedPath)) {
                            Address contextAddr = nodeAddr.and("context", ctx);
                            ops.invoke(operation, contextAddr).assertSuccess();
                            log.info("Invoked '{}' on context '{}' for node '{}' in balancer '{}'",
                                    operation, ctx, nodeName, balancerName);
                            found = true;
                            break;
                        }
                    }
                    if (found) break;
                }
            }

            client.close();

            if (!found) {
                log.warn("Context '{}' not found for node '{}' on balancer (may not be registered)",
                        normalizedPath, nodeName);
            }
        }

        @Override
        public void setMaxRetries(int maxRetries) throws Exception {
            OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .build()
            );

            Operations ops = new Operations(client);

            Address filterAddr = Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

            ops.writeAttribute(filterAddr, "max-retries", maxRetries).assertSuccess();
            log.info("Set max-retries to {} on Undertow balancer", maxRetries);

            client.close();
        }

        @Override
        public void reload() throws Exception {
            OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .build()
            );

            log.info("Reloading Undertow balancer to apply configuration changes");
            new Administration(client).reload();
            client.close();
            log.info("Undertow balancer reloaded successfully");
        }

        /**
         * Invoke an operation on a node in the mod_cluster filter.
         * Finds the node across all balancers and invokes the specified operation.
         */
        private void invokeNodeOperation(String nodeName, String operation) throws Exception {
            OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .build()
            );

            Operations ops = new Operations(client);

            Address filterAddr = Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

            List<String> balancers = ops.readChildrenNames(filterAddr, "balancer").stringListValue();

            boolean found = false;
            for (String balancerName : balancers) {
                Address balancerAddr = filterAddr.and("balancer", balancerName);
                List<String> nodes = ops.readChildrenNames(balancerAddr, "node").stringListValue();

                if (nodes.contains(nodeName)) {
                    Address nodeAddr = balancerAddr.and("node", nodeName);
                    ops.invoke(operation, nodeAddr).assertSuccess();
                    log.info("Invoked '{}' on node '{}' in balancer '{}'", operation, nodeName, balancerName);
                    found = true;
                    break;
                }
            }

            client.close();

            if (!found) {
                throw new IllegalStateException("Node '" + nodeName + "' not found on balancer");
            }
        }

        /**
         * Invoke an operation on all nodes belonging to a load-balancing group.
         * The Undertow management model does not support operations directly on
         * load-balancing-group resources, so this iterates over all nodes and invokes
         * the operation on each node whose load-balancing-group attribute matches.
         */
        private void invokeGroupOperation(String groupName, String operation) throws Exception {
            OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                    .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                    .auth("admin", "admin")
                    .build()
            );

            Operations ops = new Operations(client);

            Address filterAddr = Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

            List<String> balancers = ops.readChildrenNames(filterAddr, "balancer").stringListValue();

            int matchedNodes = 0;
            for (String balancerName : balancers) {
                Address balancerAddr = filterAddr.and("balancer", balancerName);
                List<String> nodes = ops.readChildrenNames(balancerAddr, "node").stringListValue();

                for (String nodeName : nodes) {
                    Address nodeAddr = balancerAddr.and("node", nodeName);
                    org.wildfly.extras.creaper.core.online.ModelNodeResult groupResult =
                        ops.readAttribute(nodeAddr, "load-balancing-group");

                    if (groupResult.isSuccess()) {
                        String nodeGroup = groupResult.stringValue();
                        if (groupName.equals(nodeGroup)) {
                            ops.invoke(operation, nodeAddr).assertSuccess();
                            log.info("Invoked '{}' on node '{}' (group '{}') in balancer '{}'",
                                    operation, nodeName, groupName, balancerName);
                            matchedNodes++;
                        }
                    }
                }
            }

            client.close();

            if (matchedNodes == 0) {
                throw new IllegalStateException(
                    "No nodes found in load-balancing group '" + groupName + "' on balancer");
            }

            log.info("Invoked '{}' on {} nodes in group '{}'", operation, matchedNodes, groupName);
        }

        private void startFromImage(String networkAlias) {
            String customImage = System.getProperty("balancer.undertow.image");
            String imageName = customImage != null ? customImage : "quay.io/modcluster/mod_cluster-undertow:latest";

            container = new GenericContainer<>(DockerImageName.parse(imageName))
                    .withNetwork(network)
                    .withNetworkAliases(networkAlias)
                    .withExposedPorts(HTTP_PORT, HTTPS_PORT, MCMP_PORT)
                    .waitingFor(Wait.forHttp("/").forPort(HTTP_PORT))
                    .withLogConsumer(outputFrame -> log.debug("[UNDERTOW-{}] {}",
                            networkAlias.toUpperCase(), outputFrame.getUtf8String().trim()));

            container.start();
            log.info("Undertow balancer '{}' started from pre-built image on network: {}",
                    networkAlias, network.getId());
        }

        private Path getWildFlyZipPath() {
            String zipPath = System.getProperty("wildfly.zip.path");
            if (zipPath != null) {
                return Paths.get(zipPath);
            }

            zipPath = System.getenv("WILDFLY_ZIP_PATH");
            if (zipPath != null) {
                return Paths.get(zipPath);
            }

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
            if (zipFileName.startsWith("wildfly-")) {
                String versionPart = zipFileName.substring(8);
                String majorVersion = versionPart.split("\\.")[0];

                try {
                    int major = Integer.parseInt(majorVersion);
                    return major >= 31 ? "openjdk-17" : "openjdk-11";
                } catch (NumberFormatException e) {
                    log.warn("Could not parse WildFly version, defaulting to Java 17");
                    return "openjdk-17";
                }
            } else if (zipFileName.startsWith("jboss-eap-")) {
                String versionPart = zipFileName.substring(10);
                String majorVersion = versionPart.split("\\.")[0];

                try {
                    int major = Integer.parseInt(majorVersion);
                    return major >= 8 ? "openjdk-17" : "openjdk-11";
                } catch (NumberFormatException e) {
                    log.warn("Could not parse EAP version, defaulting to Java 17");
                    return "openjdk-17";
                }
            }

            log.warn("Unknown distribution format, defaulting to Java 17");
            return "openjdk-17";
        }
    }

    /**
     * Apache httpd with mod_cluster balancer.
     */
    static class HttpdBalancerContainer extends BalancerContainer {

        @Override
        public void start() {
            type = BalancerType.HTTPD;
            network = Network.newNetwork();
            ownsNetwork = true;

            // Try custom image first, fall back to default
            String customImage = System.getProperty("balancer.httpd.image");
            String imageName = customImage != null ? customImage : "quay.io/modcluster/mod_cluster-httpd:latest";

            container = new GenericContainer<>(DockerImageName.parse(imageName))
                    .withNetwork(network)
                    .withNetworkAliases("balancer")
                    .withExposedPorts(HTTP_PORT, HTTPS_PORT, MCMP_PORT)
                    .waitingFor(Wait.forHttp("/").forPort(HTTP_PORT))
                    .withLogConsumer(outputFrame -> log.debug("[HTTPD] {}", outputFrame.getUtf8String().trim()));

            container.start();
            log.info("Httpd balancer started on network: {}", network.getId());
        }

        @Override
        public void start(Network network, String networkAlias) {
            throw new UnsupportedOperationException(
                    "Shared network start not yet implemented for httpd balancer.");
        }

        @Override
        public java.util.Map<String, org.jboss.dmr.ModelNode> getWorkerInfo() throws Exception {
            throw new UnsupportedOperationException("Worker info querying not yet implemented for httpd balancer. Use mod_cluster_manager web interface instead.");
        }

        @Override
        public List<String> getBalancerNames() throws Exception {
            throw new UnsupportedOperationException("Balancer name querying not yet implemented for httpd balancer.");
        }

        @Override
        public void disableNode(String nodeName) throws Exception {
            throw new UnsupportedOperationException("Node disable not yet implemented for httpd balancer.");
        }

        @Override
        public void stopNode(String nodeName) throws Exception {
            throw new UnsupportedOperationException("Node stop not yet implemented for httpd balancer.");
        }

        @Override
        public void enableNode(String nodeName) throws Exception {
            throw new UnsupportedOperationException("Node enable not yet implemented for httpd balancer.");
        }

        @Override
        public void disableLoadBalancingGroup(String groupName) throws Exception {
            throw new UnsupportedOperationException("Group disable not yet implemented for httpd balancer.");
        }

        @Override
        public void stopLoadBalancingGroup(String groupName) throws Exception {
            throw new UnsupportedOperationException("Group stop not yet implemented for httpd balancer.");
        }

        @Override
        public void enableLoadBalancingGroup(String groupName) throws Exception {
            throw new UnsupportedOperationException("Group enable not yet implemented for httpd balancer.");
        }

        @Override
        public List<String> getRegisteredContexts(String nodeName) throws Exception {
            throw new UnsupportedOperationException("Registered contexts query not yet implemented for httpd balancer.");
        }

        @Override
        public void disableContext(String nodeName, String contextPath) throws Exception {
            throw new UnsupportedOperationException("Context disable not yet implemented for httpd balancer.");
        }

        @Override
        public void stopContext(String nodeName, String contextPath) throws Exception {
            throw new UnsupportedOperationException("Context stop not yet implemented for httpd balancer.");
        }

        @Override
        public void enableContext(String nodeName, String contextPath) throws Exception {
            throw new UnsupportedOperationException("Context enable not yet implemented for httpd balancer.");
        }

        @Override
        public String getContextStatus(String nodeName, String contextPath) throws Exception {
            throw new UnsupportedOperationException("Context status not yet implemented for httpd balancer.");
        }

        @Override
        public void setMaxRetries(int maxRetries) throws Exception {
            throw new UnsupportedOperationException("Max-retries not yet implemented for httpd balancer.");
        }

        @Override
        public void reload() throws Exception {
            throw new UnsupportedOperationException("Reload not yet implemented for httpd balancer.");
        }
    }
}
