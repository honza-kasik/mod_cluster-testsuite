package org.jboss.modcluster.test.utils;

import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.OperationException;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;

import java.io.IOException;

/**
 * Manages ModCluster subsystem configuration for WildFly containers.
 * Handles proxy configuration and attribute management.
 */
public class WildFlyModClusterManager {

    private static final Logger log = LoggerFactory.getLogger(WildFlyModClusterManager.class);

    private final WildFlyContainer container;

    private String mcmpListener = "default";
    private int mcmpPort = 8080;
    private String mcmpSslContext;

    WildFlyModClusterManager(WildFlyContainer container) {
        this.container = container;
    }

    /**
     * Configure MCMP channel to use SSL/TLS.
     * Settings persist across reloads since {@link #configureStaticProxy()} uses these values.
     *
     * @param listener Undertow listener name ("default" for HTTP, "https" for HTTPS)
     * @param port port for outbound-socket-binding to balancer
     * @param sslContext Elytron client-ssl-context name for MCMP, or null for plain HTTP
     */
    public void setMcmpSslConfig(final String listener, final int port, final String sslContext) {
        this.mcmpListener = listener;
        this.mcmpPort = port;
        this.mcmpSslContext = sslContext;
        log.info("MCMP SSL config set: listener='{}', port={}, sslContext='{}' on worker '{}'",
                listener, port, sslContext, container.getName());
    }

    /**
     * Configure static proxy connection to the balancer.
     * Creates an outbound-socket-binding and configures mod_cluster to use it.
     * Uses configurable listener, port, and SSL context set via {@link #setMcmpSslConfig}.
     */
    public void configureStaticProxy() {
        try {
            OnlineManagementClient client = container.getManagementClient();
            Operations ops = container.getOperations();

            // Step 1: Create outbound-socket-binding to balancer
            log.info("Creating outbound-socket-binding for balancer (port={})", mcmpPort);

            Address socketBindingAddr = Address.of("socket-binding-group", "standard-sockets")
                    .and("remote-destination-outbound-socket-binding", "modcluster-balancer");

            ModelNode addSocketBinding = new ModelNode();
            ModelNode address = addSocketBinding.get("address");
            address.add("socket-binding-group", "standard-sockets");
            address.add("remote-destination-outbound-socket-binding", "modcluster-balancer");
            addSocketBinding.get("operation").set("add");
            addSocketBinding.get("host").set("balancer");
            addSocketBinding.get("port").set(mcmpPort);

            ModelNode result = client.execute(addSocketBinding);
            if (!result.get("outcome").asString().equals("success")) {
                log.debug("Socket binding may already exist: {}", result.get("failure-description").asString());

                // If it already exists and port differs from default, update it
                if (mcmpPort != 8080) {
                    ops.writeAttribute(socketBindingAddr, "port", mcmpPort).assertSuccess();
                    log.info("Updated existing socket binding port to {}", mcmpPort);
                }
            }

            // Step 2: Set proxy list to use the outbound-socket-binding
            Address mcProxyAddress = Address.subsystem("modcluster").and("proxy", "default");
            ModelNode proxyList = new ModelNode();
            proxyList.add("modcluster-balancer");

            ModelNodeResult writeResult =
                ops.writeAttribute(mcProxyAddress, "proxies", proxyList);
            writeResult.assertSuccess();

            // Step 3: Set listener for MCMP communication
            ModelNodeResult listenerResult =
                ops.writeAttribute(mcProxyAddress, "listener", mcmpListener);
            listenerResult.assertSuccess();

            // Step 4: Set SSL context on mod_cluster proxy if configured
            if (mcmpSslContext != null) {
                ModelNodeResult sslResult =
                    ops.writeAttribute(mcProxyAddress, "ssl-context", mcmpSslContext);
                sslResult.assertSuccess();
                log.info("MCMP SSL context set to '{}' on worker '{}'", mcmpSslContext, container.getName());
            }

            log.info("Mod_cluster static proxy configured successfully on worker '{}' (listener='{}', port={})",
                    container.getName(), mcmpListener, mcmpPort);

            // Wait for the proxy connection to establish
            Thread.sleep(5000);

        } catch (Exception e) {
            log.error("Failed to configure static proxy on worker '{}'", container.getName(), e);
        }
    }

    /**
     * Read a mod_cluster subsystem attribute.
     *
     * @param attributeName The name of the attribute to read
     * @return The attribute value as a ModelNode
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public ModelNode readModClusterAttribute(String attributeName) throws IOException, OperationException {
        Operations ops = container.getOperations();
        Address modclusterAddress = Address.subsystem("modcluster").and("proxy", "default");
        ModelNodeResult result = ops.readAttribute(modclusterAddress, attributeName);
        result.assertSuccess();
        return result.value();
    }

    /**
     * Write a mod_cluster subsystem attribute.
     *
     * @param attributeName The name of the attribute to write
     * @param value The value to set (supports Boolean, Integer, Long, String, ModelNode)
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void writeModClusterAttribute(String attributeName, Object value) throws IOException, OperationException {
        Operations ops = container.getOperations();
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
        log.info("Set mod_cluster attribute '{}' to '{}' on worker '{}'", attributeName, value, container.getName());
    }

    /**
     * Set the session draining strategy on this worker's mod_cluster subsystem.
     * Controls whether sessions are drained before stopping a context.
     *
     * @param strategy The strategy to use: "ALWAYS", "NEVER", or "DEFAULT"
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void setSessionDrainingStrategy(String strategy) throws IOException, OperationException {
        writeModClusterAttribute("session-draining-strategy", strategy);
        log.info("Set session-draining-strategy to '{}' on worker '{}'", strategy, container.getName());
    }

    /**
     * Disable a context on this worker. The context will reject new sessions
     * but continue serving existing sessions.
     *
     * @param contextPath Context path (e.g., "demo" or "/demo")
     * @param virtualHost Virtual host name (e.g., "default-host")
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void disableContext(String contextPath, String virtualHost)
        throws IOException, OperationException {
        Operations ops = container.getOperations();
        Address modclusterAddress = Address.subsystem("modcluster").and("proxy", "default");

        // Normalize context path to have leading slash
        String normalizedContext = contextPath.startsWith("/") ? contextPath : "/" + contextPath;

        ModelNodeResult result = ops.invoke(
            "disable-context",
            modclusterAddress,
            Values.of("context", normalizedContext)
                  .and("virtualhost", virtualHost)
        );

        result.assertSuccess();
        log.info("Disabled context '{}' on virtualhost '{}' for worker '{}'",
                 normalizedContext, virtualHost, container.getName());
    }

    /**
     * Enable a previously disabled context on this worker.
     *
     * @param contextPath Context path (e.g., "demo" or "/demo")
     * @param virtualHost Virtual host name (e.g., "default-host")
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void enableContext(String contextPath, String virtualHost)
        throws IOException, OperationException {
        Operations ops = container.getOperations();
        Address modclusterAddress = Address.subsystem("modcluster").and("proxy", "default");

        String normalizedContext = contextPath.startsWith("/") ? contextPath : "/" + contextPath;

        ModelNodeResult result = ops.invoke(
            "enable-context",
            modclusterAddress,
            Values.of("context", normalizedContext)
                  .and("virtualhost", virtualHost)
        );

        result.assertSuccess();
        log.info("Enabled context '{}' on virtualhost '{}' for worker '{}'",
                 normalizedContext, virtualHost, container.getName());
    }

    /**
     * Stop a context on this worker. The context will drain sessions
     * according to stop-context-timeout and session-draining-strategy.
     *
     * @param contextPath Context path (e.g., "demo" or "/demo")
     * @param virtualHost Virtual host name (e.g., "default-host")
     * @throws IOException if there's a connection error
     * @throws OperationException if the management operation fails
     */
    public void stopContext(String contextPath, String virtualHost)
        throws IOException, OperationException {
        Operations ops = container.getOperations();
        Address modclusterAddress = Address.subsystem("modcluster").and("proxy", "default");

        String normalizedContext = contextPath.startsWith("/") ? contextPath : "/" + contextPath;

        ModelNodeResult result = ops.invoke(
            "stop-context",
            modclusterAddress,
            Values.of("context", normalizedContext)
                  .and("virtualhost", virtualHost)
        );

        result.assertSuccess();
        log.info("Stopped context '{}' on virtualhost '{}' for worker '{}'",
                 normalizedContext, virtualHost, container.getName());
    }
}
