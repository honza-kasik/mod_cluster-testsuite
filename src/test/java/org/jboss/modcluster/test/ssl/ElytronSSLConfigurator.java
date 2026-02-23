package org.jboss.modcluster.test.ssl;

import org.jboss.dmr.ModelNode;
import org.jboss.modcluster.test.utils.BalancerContainer;
import org.jboss.modcluster.test.utils.WildFlyContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;

/**
 * Configures Elytron subsystem for SSL/TLS communication.
 * Creates key stores, trust managers, and SSL contexts for workers and balancers.
 * Targets current WildFly versions (11+/EAP 7.1+) with Elytron security.
 */
public class ElytronSSLConfigurator {

    private static final Logger log = LoggerFactory.getLogger(ElytronSSLConfigurator.class);

    /**
     * Configures Elytron SSL on a worker.
     * Uses default WildFly application keystore for demonstration purposes.
     * In production, proper certificates and keystores should be configured.
     *
     * @param worker Container to configure
     * @throws Exception if configuration fails
     */
    public void configureElytronSSL(final WildFlyContainer worker) throws Exception {
        log.info("Configuring Elytron SSL on worker '{}'", worker.getName());

        final Operations ops = worker.getOperations();

        // Step 1: Ensure key-store exists (applicationKS is pre-configured in WildFly)
        final Address keyStoreAddr = Address.subsystem("elytron").and("key-store", "applicationKS");
        if (!ops.exists(keyStoreAddr)) {
            log.debug("Creating applicationKS key-store");
            // Path to default keystore in WildFly
            // Construct credential-reference as ModelNode
            final ModelNode credentialRef = new ModelNode();
            credentialRef.get("clear-text").set("password");

            ops.add(keyStoreAddr, Values.of("path", "application.keystore")
                .and("relative-to", "jboss.server.config.dir")
                .and("credential-reference", credentialRef)
                .and("type", "JKS"))
                .assertSuccess();
        }

        // Step 2: Create or verify key-manager
        final Address keyManagerAddr = Address.subsystem("elytron").and("key-manager", "applicationKM");
        if (!ops.exists(keyManagerAddr)) {
            log.debug("Creating applicationKM key-manager");
            // Construct credential-reference as ModelNode
            final ModelNode credentialRef = new ModelNode();
            credentialRef.get("clear-text").set("password");

            ops.add(keyManagerAddr, Values.of("key-store", "applicationKS")
                .and("credential-reference", credentialRef))
                .assertSuccess();
        }

        // Step 3: Create server-ssl-context
        final Address sslContextAddr = Address.subsystem("elytron").and("server-ssl-context", "applicationSSC");
        if (!ops.exists(sslContextAddr)) {
            log.debug("Creating applicationSSC server-ssl-context");
            ops.add(sslContextAddr, Values.of("key-manager", "applicationKM"))
                .assertSuccess();
        }

        // Step 4: Link SSL context to Undertow HTTPS listener
        final Address httpsListenerAddr = Address.subsystem("undertow")
            .and("server", "default-server")
            .and("https-listener", "https");

        if (!ops.exists(httpsListenerAddr)) {
            log.debug("Creating HTTPS listener");
            // Create HTTPS listener if it doesn't exist
            ops.add(httpsListenerAddr, Values.of("socket-binding", "https")
                .and("ssl-context", "applicationSSC"))
                .assertSuccess();
        } else {
            log.debug("Linking SSL context to existing HTTPS listener");
            ops.writeAttribute(httpsListenerAddr, "ssl-context", "applicationSSC").assertSuccess();
        }

        // Reload to apply changes
        log.debug("Reloading server to apply Elytron SSL configuration");
        worker.reload();

        log.info("Elytron SSL configured successfully on worker '{}'", worker.getName());
    }

    /**
     * Configures Elytron SSL on a balancer.
     * Currently minimal implementation as workers connect via HTTP to balancer.
     * Full HTTPS frontend configuration would be added here if needed.
     *
     * @param balancer Container to configure
     * @throws Exception if configuration fails
     */
    public void configureBalancerElytronSSL(final BalancerContainer balancer) throws Exception {
        log.info("Configuring Elytron SSL on balancer");

        // For Undertow balancer, workers connect via HTTP to MCMP port
        // HTTPS frontend configuration could be added here if testing HTTPS client -> balancer connections

        log.debug("Balancer Elytron SSL configuration not yet implemented (workers use HTTP for MCMP)");
    }

    /**
     * Removes Elytron SSL configuration from a worker, reverting to default.
     * Useful for cleanup between tests.
     *
     * @param worker Container to reset
     * @throws Exception if reset fails
     */
    public void removeElytronSSL(final WildFlyContainer worker) throws Exception {
        log.info("Removing Elytron SSL configuration from worker '{}'", worker.getName());

        final Operations ops = worker.getOperations();

        // Unlink SSL context from HTTPS listener (revert to legacy security-realm)
        final Address httpsListenerAddr = Address.subsystem("undertow")
            .and("server", "default-server")
            .and("https-listener", "https");

        if (ops.exists(httpsListenerAddr)) {
            try {
                ops.undefineAttribute(httpsListenerAddr, "ssl-context");
                log.debug("Unlinked SSL context from HTTPS listener");
            } catch (Exception e) {
                log.debug("Could not unlink SSL context: {}", e.getMessage());
            }
        }

        // Reload to apply changes
        worker.reload();

        log.info("Elytron SSL configuration removed from worker '{}'", worker.getName());
    }
}
