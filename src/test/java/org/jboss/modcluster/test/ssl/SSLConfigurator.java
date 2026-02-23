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
 *
 * <p>Also supports mutual TLS (mTLS) with MCMP-over-SSL for CRL testing scenarios,
 * where both client and server certificates are configured and the mod_cluster
 * management channel communicates via HTTPS.</p>
 */
public class SSLConfigurator {

    private static final Logger log = LoggerFactory.getLogger(SSLConfigurator.class);

    private static final String KEYSTORE_PASSWORD = "testpass";
    private static final String SSL_DIR = "/opt/wildfly/standalone/configuration/ssl";
    private static final String KEYSTORES_RESOURCE_DIR = "ssl/ca/intermediate/keystores/";
    private static final String CRL_RESOURCE_PATH = "ssl/ca/intermediate/crl/intermediate.crl.pem";
    private static final int MANAGEMENT_PORT = 9990;
    private static final int MCMP_HTTPS_PORT = 8443;

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

    /**
     * Configures full mutual TLS and MCMP-over-SSL on a worker.
     * Copies server, client, and trust keystores into the container, creates Elytron resources
     * for both server and client SSL contexts with {@code need-client-auth=true},
     * links the server SSL context to the HTTPS listener, and switches the mod_cluster
     * proxy's MCMP management channel to use TLS on port 8443 (the balancer's HTTPS listener).
     *
     * <p>The worker's listener stays as "default" (HTTP) so the balancer's health-check and
     * proxy connections go to the worker's plain HTTP port. Only the MCMP management channel
     * (worker → balancer) uses TLS, which is where CRL enforcement takes effect.</p>
     *
     * <p>All management model changes (Elytron, HTTPS listener, mod_cluster attributes,
     * socket binding port) are written before the reload so the server boots with them.</p>
     *
     * @param worker worker container to configure
     * @param serverKeystore server keystore name prefix (e.g., "node1.server" or "node4.server")
     * @param clientKeystore client keystore name prefix (e.g., "node1.client" or "node4.client.revoked")
     * @throws Exception if configuration fails
     */
    public void configureMtlsWorker(final WildFlyContainer worker, final String serverKeystore,
                                     final String clientKeystore) throws Exception {
        log.info("Configuring mTLS + MCMP-over-SSL on worker '{}' (server={}, client={})",
                worker.getName(), serverKeystore, clientKeystore);

        copyMtlsKeystores(worker.getContainer(), serverKeystore, clientKeystore);

        final Operations ops = worker.getOperations();
        createMtlsElytronResources(ops);
        linkToHttpsListener(ops);

        // Write MCMP-over-SSL settings to management model BEFORE reload —
        // socket binding port changes only take effect after a reload.
        // listener stays "default" (HTTP) so the balancer's health-check connections
        // to workers use plain HTTP. TLS is only needed on the MCMP management channel
        // (worker → balancer), controlled by ssl-context below.
        final Address mcProxy = Address.subsystem("modcluster").and("proxy", "default");
        ops.writeAttribute(mcProxy, "ssl-context", "clientSSLContext").assertSuccess();

        final Address outboundSocket = Address.of("socket-binding-group", "standard-sockets")
                .and("remote-destination-outbound-socket-binding", "modcluster-balancer");
        ops.writeAttribute(outboundSocket, "port", MCMP_HTTPS_PORT).assertSuccess();

        // Also set on the manager so any future reload() calls preserve these settings
        worker.modCluster().setMcmpSslConfig("default", MCMP_HTTPS_PORT, "clientSSLContext");

        // reloadServer() applies all changes without re-running configureStaticProxy()
        worker.reloadServer();

        log.info("mTLS + MCMP-over-SSL configured successfully on worker '{}'", worker.getName());
    }

    /**
     * Configures full mutual TLS and MCMP-over-SSL on the Undertow balancer.
     * Copies server, client, and trust keystores, creates Elytron resources with
     * {@code need-client-auth=true}, links the server SSL context to the HTTPS listener,
     * and switches the mod_cluster filter's management channel to the HTTPS socket binding.
     *
     * @param balancer balancer container to configure
     * @param serverKeystore server keystore name prefix (e.g., "node2.server")
     * @param clientKeystore client keystore name prefix (e.g., "node2.client")
     * @throws Exception if configuration fails
     */
    public void configureMtlsBalancer(final BalancerContainer balancer, final String serverKeystore,
                                       final String clientKeystore) throws Exception {
        log.info("Configuring mTLS + MCMP-over-SSL on balancer (server={}, client={})",
                serverKeystore, clientKeystore);

        copyMtlsKeystores(balancer.getContainer(), serverKeystore, clientKeystore);

        try (OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                        .hostAndPort(balancer.getContainer().getHost(),
                                balancer.getContainer().getMappedPort(MANAGEMENT_PORT))
                        .auth("admin", "admin")
                        .connectionTimeout(30000)
                        .build())) {
            final Operations ops = new Operations(client);
            createMtlsElytronResources(ops);
            linkToHttpsListener(ops);
            configureMcmpOverSslOnBalancer(ops);

            new Administration(client).reload();
        }

        log.info("mTLS + MCMP-over-SSL configured successfully on balancer");
    }

    /**
     * Adds a Certificate Revocation List (CRL) to the trust-manager on a worker.
     * Copies the CRL file into the container, configures the trust-manager to use it,
     * and reloads the server to force existing TLS connections to drop.
     * Workers reconnect with a new TLS handshake that checks the CRL.
     *
     * @param worker worker container to add CRL to
     * @throws Exception if configuration fails
     */
    public void addCrlToWorker(final WildFlyContainer worker) throws Exception {
        log.info("Adding CRL to worker '{}'", worker.getName());

        copyFileWithRetry(worker.getContainer(), CRL_RESOURCE_PATH, SSL_DIR + "/intermediate.crl.pem");

        final Operations ops = worker.getOperations();
        writeCrlAttribute(ops);

        // Reload to drop existing TLS connections — new handshakes will check the CRL
        worker.reloadServer();

        log.info("CRL added successfully to worker '{}'", worker.getName());
    }

    /**
     * Adds a Certificate Revocation List (CRL) to the trust-manager on the balancer.
     * Copies the CRL file into the container, configures the trust-manager to use it,
     * and reloads the server to force existing TLS connections to drop.
     * Workers reconnect with a new TLS handshake that checks the CRL.
     *
     * @param balancer balancer container to add CRL to
     * @throws Exception if configuration fails
     */
    public void addCrlToBalancer(final BalancerContainer balancer) throws Exception {
        log.info("Adding CRL to balancer");

        copyFileWithRetry(balancer.getContainer(), CRL_RESOURCE_PATH, SSL_DIR + "/intermediate.crl.pem");

        try (OnlineManagementClient client = ManagementClient.online(
                OnlineOptions.standalone()
                        .hostAndPort(balancer.getContainer().getHost(),
                                balancer.getContainer().getMappedPort(MANAGEMENT_PORT))
                        .auth("admin", "admin")
                        .connectionTimeout(30000)
                        .build())) {
            final Operations ops = new Operations(client);
            writeCrlAttribute(ops);

            // Reload to drop existing TLS connections — new handshakes will check the CRL
            new Administration(client).reload();
        }

        log.info("CRL added successfully to balancer");
    }

    // ---- Private helpers for server-only SSL (existing) ----

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
        final String serverKeystoreResource = KEYSTORES_RESOURCE_DIR + nodeName + ".server.keystore.jks";
        final String trustStoreResource = KEYSTORES_RESOURCE_DIR + "ca-chain.keystore.jks";

        log.debug("Copying server keystore '{}' into container '{}'", serverKeystoreResource, containerName);
        copyFileWithRetry(container, serverKeystoreResource, SSL_DIR + "/server.keystore.jks");

        log.debug("Copying CA chain trust store into container '{}'", containerName);
        copyFileWithRetry(container, trustStoreResource, SSL_DIR + "/ca-chain.keystore.jks");
    }

    /**
     * Copies server, client, and CA chain trust keystores into the container for mTLS.
     *
     * @param container target container
     * @param serverKeystore server keystore name prefix (e.g., "node1.server" or "node3.server.revoked")
     * @param clientKeystore client keystore name prefix (e.g., "node1.client" or "node4.client.revoked")
     */
    private void copyMtlsKeystores(final GenericContainer<?> container, final String serverKeystore,
                                    final String clientKeystore) {
        final String serverResource = KEYSTORES_RESOURCE_DIR + serverKeystore + ".keystore.jks";
        final String clientResource = KEYSTORES_RESOURCE_DIR + clientKeystore + ".keystore.jks";
        final String trustResource = KEYSTORES_RESOURCE_DIR + "ca-chain.keystore.jks";

        log.debug("Copying server keystore '{}'", serverResource);
        copyFileWithRetry(container, serverResource, SSL_DIR + "/server.keystore.jks");

        log.debug("Copying client keystore '{}'", clientResource);
        copyFileWithRetry(container, clientResource, SSL_DIR + "/client.keystore.jks");

        log.debug("Copying CA chain trust store");
        copyFileWithRetry(container, trustResource, SSL_DIR + "/ca-chain.keystore.jks");
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

    // ---- Elytron resource creation ----

    /**
     * Creates Elytron key-store, key-manager, trust-manager, and server-ssl-context resources.
     * Used for server-only SSL (no client certificate, no mTLS).
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
     * Creates Elytron resources for full mutual TLS: trust store, trust-manager,
     * server key-store/key-manager with need-client-auth, client key-store/key-manager,
     * server-ssl-context, and client-ssl-context.
     *
     * @param ops Creaper operations handle
     * @throws Exception if any management operation fails
     */
    private void createMtlsElytronResources(final Operations ops) throws Exception {
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

        // Server SSL context with need-client-auth for mutual TLS
        final Address serverSslContextAddr = Address.subsystem("elytron").and("server-ssl-context", "serverSSLContext");
        if (!ops.exists(serverSslContextAddr)) {
            log.debug("Creating serverSSLContext server-ssl-context with need-client-auth");
            ops.add(serverSslContextAddr, Values.of("key-manager", "serverKeyManager")
                    .and("trust-manager", "trustStoreManager")
                    .and("need-client-auth", true))
                    .assertSuccess();
        }

        // Client key-store
        final Address clientKeyStoreAddr = Address.subsystem("elytron").and("key-store", "clientKeyStore");
        if (!ops.exists(clientKeyStoreAddr)) {
            log.debug("Creating clientKeyStore key-store");
            ops.add(clientKeyStoreAddr, Values.of("path", SSL_DIR + "/client.keystore.jks")
                    .and("credential-reference", credentialRef)
                    .and("type", "JKS"))
                    .assertSuccess();
        }

        // Client key-manager
        final Address clientKeyManagerAddr = Address.subsystem("elytron").and("key-manager", "clientKeyManager");
        if (!ops.exists(clientKeyManagerAddr)) {
            log.debug("Creating clientKeyManager key-manager");
            ops.add(clientKeyManagerAddr, Values.of("key-store", "clientKeyStore")
                    .and("credential-reference", credentialRef))
                    .assertSuccess();
        }

        // Client SSL context (for outbound MCMP connections)
        final Address clientSslContextAddr = Address.subsystem("elytron").and("client-ssl-context", "clientSSLContext");
        if (!ops.exists(clientSslContextAddr)) {
            log.debug("Creating clientSSLContext client-ssl-context");
            ops.add(clientSslContextAddr, Values.of("key-manager", "clientKeyManager")
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

    // ---- MCMP-over-SSL configuration ----

    /**
     * Switches the mod_cluster filter's management channel from HTTP to HTTPS.
     * Changes the {@code management-socket-binding} from {@code http} (port 8080)
     * to {@code https} (port 8443) so the management handler shares the HTTPS listener's socket.
     * Sets {@code ssl-context=clientSSLContext} on the filter for outbound connections,
     * while inbound TLS is handled by the HTTPS listener's {@code serverSSLContext}.
     *
     * <p>This matches the noe-tests approach: the HTTPS listener handles TLS termination
     * (with {@code need-client-auth=true}), and the mod_cluster management handler
     * processes MCMP requests on the decrypted channel.</p>
     *
     * @param ops Creaper operations handle
     * @throws Exception if any management operation fails
     */
    private void configureMcmpOverSslOnBalancer(final Operations ops) throws Exception {
        final Address filterAddr = Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

        // Switch management channel from http (HTTP/8080) to https (HTTPS/8443)
        ops.writeAttribute(filterAddr, "management-socket-binding", "https").assertSuccess();

        // Set client SSL context for outbound connections from balancer to workers
        ops.writeAttribute(filterAddr, "ssl-context", "clientSSLContext").assertSuccess();

        log.info("MCMP management channel switched to HTTPS socket binding with clientSSLContext");
    }

    // ---- CRL configuration ----

    /**
     * Writes the certificate-revocation-list attribute on the trust-manager
     * to enable CRL checking.
     *
     * @param ops Creaper operations handle
     * @throws Exception if the management operation fails
     */
    private void writeCrlAttribute(final Operations ops) throws Exception {
        final Address trustManagerAddr = Address.subsystem("elytron").and("trust-manager", "trustStoreManager");

        final ModelNode crlValue = new ModelNode();
        crlValue.get("path").set(SSL_DIR + "/intermediate.crl.pem");

        log.debug("Setting certificate-revocation-list on trustStoreManager");
        ops.writeAttribute(trustManagerAddr, "certificate-revocation-list", crlValue).assertSuccess();
    }
}
