package org.jboss.modcluster.test.ssl;

import org.jboss.dmr.ModelNode;
import org.jboss.modcluster.test.utils.BalancerContainer;
import org.jboss.modcluster.test.utils.ContainerUtils;
import org.jboss.modcluster.test.utils.WildFlyContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.MountableFile;
import org.wildfly.extras.creaper.core.ManagementClient;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.OnlineOptions;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;
import org.wildfly.extras.creaper.core.online.operations.admin.Administration;

/**
 * Configures Elytron SSL/TLS using proper PKI certificates.
 * Copies node-specific server keystores and CA trust chain into containers,
 * then creates Elytron key-store, key-manager, trust-manager, and server-ssl-context
 * resources linked to the Undertow HTTPS listener.
 *
 * <p>Supports both workers and balancers. Workers get node-specific server certificates
 * (node1/node2), while the balancer gets the localhost server certificate.</p>
 */
public class SSLConfigurator {

    private static final Logger log = LoggerFactory.getLogger(SSLConfigurator.class);

    private static final String KEYSTORE_PASSWORD = "testpass";
    private static final String SSL_DIR = "/opt/wildfly/standalone/configuration/ssl";
    private static final int MANAGEMENT_PORT = 9990;

    /**
     * Configures Elytron SSL on a worker using proper PKI certificates.
     * Copies the correct node-specific server keystore and CA chain trust store into the container,
     * creates Elytron resources (key-store, key-manager, trust-manager, server-ssl-context),
     * and links the SSL context to the Undertow HTTPS listener.
     *
     * @param worker container to configure
     * @throws Exception if configuration fails
     */
    public void configureWorker(final WildFlyContainer worker) throws Exception {
        log.info("Configuring SSL on worker '{}'", worker.getName());

        copyKeystores(worker.getContainer(), worker.getName());

        final Operations ops = worker.getOperations();
        createElytronResources(ops);
        linkToHttpsListener(ops);

        worker.reload();

        log.info("SSL configured successfully on worker '{}'", worker.getName());
    }

    /**
     * Configures Elytron SSL on an Undertow balancer using the localhost server certificate.
     * Creates a management client, copies keystores, configures Elytron resources,
     * links to HTTPS listener, and reloads the server.
     *
     * @param balancer balancer container to configure
     * @throws Exception if configuration fails
     */
    public void configureBalancer(final BalancerContainer balancer) throws Exception {
        log.info("Configuring SSL on balancer");

        copyKeystores(balancer.getContainer(), "balancer");

        try (OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                        .hostAndPort(balancer.getContainer().getHost(),
                                balancer.getContainer().getMappedPort(MANAGEMENT_PORT))
                        .auth("admin", "admin")
                        .connectionTimeout(30000)
                        .build())) {
            final Operations ops = new Operations(client);
            createElytronResources(ops);
            linkToHttpsListener(ops);

            new Administration(client).reload();
        }

        log.info("SSL configured successfully on balancer");
    }

    private static final int MAX_COPY_RETRIES = 3;
    private static final long COPY_RETRY_DELAY_MS = 500;

    /**
     * Copies the appropriate server keystore and CA chain trust store into the container.
     * Maps container name to the corresponding node keystore:
     * worker1 to node1, worker2 to node2, balancer to localhost.
     * Uses file mode 0644 so the WildFly process can read the files regardless of container user.
     * Retries on transient Podman SIGPIPE errors.
     */
    private void copyKeystores(final GenericContainer<?> container, final String containerName) {
        final String nodeName = mapToNodeName(containerName);
        final String serverKeystoreResource = "ssl/ca/intermediate/keystores/" + nodeName + ".server.keystore.jks";
        final String trustStoreResource = "ssl/ca/intermediate/keystores/ca-chain.keystore.jks";

        log.debug("Copying server keystore '{}' into container '{}'", serverKeystoreResource, containerName);
        copyFileWithRetry(container, serverKeystoreResource, SSL_DIR + "/server.keystore.jks");

        log.debug("Copying CA chain trust store into container '{}'", containerName);
        copyFileWithRetry(container, trustStoreResource, SSL_DIR + "/ca-chain.keystore.jks");
    }

    /**
     * Copies a classpath resource into the container with retry logic for transient Podman SIGPIPE errors.
     *
     * @param container target container
     * @param classpathResource resource path on classpath
     * @param containerPath destination path inside the container
     */
    private void copyFileWithRetry(final GenericContainer<?> container, final String classpathResource,
                                   final String containerPath) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_COPY_RETRIES; attempt++) {
            try {
                container.copyFileToContainer(
                        MountableFile.forClasspathResource(classpathResource, 0644),
                        containerPath);
                return;
            } catch (Exception e) {
                lastException = e;

                if (ContainerUtils.isTransientDockerError(e) && attempt < MAX_COPY_RETRIES) {
                    log.warn("Transient error copying '{}' on attempt {}/{}, retrying",
                            classpathResource, attempt, MAX_COPY_RETRIES);
                    try {
                        Thread.sleep(COPY_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during copy retry", ie);
                    }
                } else {
                    break;
                }
            }
        }

        throw new RuntimeException("Failed to copy '" + classpathResource + "' after " + MAX_COPY_RETRIES + " attempts",
                lastException);
    }

    /**
     * Maps container name to the corresponding node name used in keystore filenames.
     *
     * @param containerName container name (e.g. "worker1", "worker2", "balancer")
     * @return node name (e.g. "node1", "node2", "localhost")
     */
    private String mapToNodeName(final String containerName) {
        if ("balancer".equals(containerName)) {
            return "localhost";
        }
        return containerName.replace("worker", "node");
    }

    /**
     * Creates Elytron key-store, key-manager, trust-manager, and server-ssl-context resources.
     *
     * @param ops Creaper operations handle
     * @throws Exception if any management operation fails
     */
    private void createElytronResources(final Operations ops) throws Exception {
        final ModelNode credentialRef = new ModelNode();
        credentialRef.get("clear-text").set(KEYSTORE_PASSWORD);

        // Trust key-store (CA chain)
        final Address trustKeyStoreAddr = Address.subsystem("elytron").and("key-store", "trustKeyStore");
        if (!ops.exists(trustKeyStoreAddr)) {
            log.debug("Creating trustKeyStore key-store");
            ops.add(trustKeyStoreAddr, Values.of("path", SSL_DIR + "/ca-chain.keystore.jks")
                    .and("credential-reference", credentialRef)
                    .and("type", "JKS"))
                    .assertSuccess();
        }

        // Trust manager
        final Address trustManagerAddr = Address.subsystem("elytron").and("trust-manager", "trustStoreManager");
        if (!ops.exists(trustManagerAddr)) {
            log.debug("Creating trustStoreManager trust-manager");
            ops.add(trustManagerAddr, Values.of("key-store", "trustKeyStore"))
                    .assertSuccess();
        }

        // Server key-store
        final Address serverKeyStoreAddr = Address.subsystem("elytron").and("key-store", "serverKeyStore");
        if (!ops.exists(serverKeyStoreAddr)) {
            log.debug("Creating serverKeyStore key-store");
            ops.add(serverKeyStoreAddr, Values.of("path", SSL_DIR + "/server.keystore.jks")
                    .and("credential-reference", credentialRef)
                    .and("type", "JKS"))
                    .assertSuccess();
        }

        // Server key-manager
        final Address serverKeyManagerAddr = Address.subsystem("elytron").and("key-manager", "serverKeyManager");
        if (!ops.exists(serverKeyManagerAddr)) {
            log.debug("Creating serverKeyManager key-manager");
            ops.add(serverKeyManagerAddr, Values.of("key-store", "serverKeyStore")
                    .and("credential-reference", credentialRef))
                    .assertSuccess();
        }

        // Server SSL context
        final Address sslContextAddr = Address.subsystem("elytron").and("server-ssl-context", "serverSSLContext");
        if (!ops.exists(sslContextAddr)) {
            log.debug("Creating serverSSLContext server-ssl-context");
            ops.add(sslContextAddr, Values.of("key-manager", "serverKeyManager")
                    .and("trust-manager", "trustStoreManager"))
                    .assertSuccess();
        }
    }

    /**
     * Links the server SSL context to the Undertow HTTPS listener.
     * Removes the legacy security-realm attribute first, as it conflicts with ssl-context.
     *
     * @param ops Creaper operations handle
     * @throws Exception if any management operation fails
     */
    private void linkToHttpsListener(final Operations ops) throws Exception {
        final Address httpsListenerAddr = Address.subsystem("undertow")
                .and("server", "default-server")
                .and("https-listener", "https");

        log.debug("Removing legacy security-realm from HTTPS listener");
        ops.undefineAttribute(httpsListenerAddr, "security-realm").assertSuccess();

        log.debug("Linking serverSSLContext to HTTPS listener");
        ops.writeAttribute(httpsListenerAddr, "ssl-context", "serverSSLContext").assertSuccess();
    }
}
