package org.jboss.modcluster.test.ssl;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.HttpClient.HttpResponse;
import org.jboss.modcluster.test.utils.WildFlyContainer;
import org.jboss.modcluster.test.utils.WildFlyDeploymentManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Advanced SSL/TLS failover testing.
 * Tests failover scenarios over HTTPS with both legacy and Elytron configurations.
 * Targets current WildFly versions (11+/EAP 7.1+).
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class AdvancedSSLTest {

    private static final Logger log = LoggerFactory.getLogger(AdvancedSSLTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies HTTPS failover during graceful worker shutdown.
     * Tests that SSL/TLS connections continue to work after worker shutdown.
     * Passes if HTTPS requests succeed across 3 shutdown/restart cycles.
     */
    @Test
    public void testSslFailoverViaShutdown(TestCluster cluster, HttpClient httpClient) throws Exception {
        testSslFailoverPattern(cluster, httpClient, WildFlyContainer::stop, "shutdown", false);
    }

    /**
     * Verifies HTTPS failover during hard worker kill.
     * Tests that SSL/TLS connections continue to work after SIGKILL.
     * Passes if HTTPS requests succeed across 3 kill/restart cycles.
     */
    @Test
    public void testSslFailoverViaKill(TestCluster cluster, HttpClient httpClient) throws Exception {
        testSslFailoverPattern(cluster, httpClient, WildFlyContainer::kill, "kill", false);
    }

    /**
     * Verifies HTTPS failover during application undeploy.
     * Tests that SSL/TLS connections continue to work after context undeploy.
     * Passes if HTTPS requests succeed across 3 undeploy cycles.
     */
    @Test
    public void testSslFailoverViaUndeploy(TestCluster cluster, HttpClient httpClient) throws Exception {
        testSslFailoverPattern(cluster, httpClient,
            worker -> new WildFlyDeploymentManager(worker).undeploy("demo.war"),
            "undeploy", false);
    }

    /**
     * Verifies HTTPS failover during graceful shutdown with Elytron SSL.
     * Tests Elytron-based SSL connections continue to work after worker shutdown.
     * Passes if HTTPS requests succeed across 3 shutdown/restart cycles using Elytron.
     */
    @Test
    public void testSslFailoverViaShutdownWithElytron(TestCluster cluster, HttpClient httpClient) throws Exception {
        testSslFailoverPattern(cluster, httpClient, WildFlyContainer::stop, "shutdown", true);
    }

    /**
     * Verifies HTTPS failover during hard kill with Elytron SSL.
     * Tests Elytron-based SSL connections continue to work after SIGKILL.
     * Passes if HTTPS requests succeed across 3 kill/restart cycles using Elytron.
     */
    @Test
    public void testSslFailoverViaKillWithElytron(TestCluster cluster, HttpClient httpClient) throws Exception {
        testSslFailoverPattern(cluster, httpClient, WildFlyContainer::kill, "kill", true);
    }

    /**
     * Verifies HTTPS failover during undeploy with Elytron SSL.
     * Tests Elytron-based SSL connections continue to work after context undeploy.
     * Passes if HTTPS requests succeed across 3 undeploy cycles using Elytron.
     */
    @Test
    public void testSslFailoverViaUndeployWithElytron(TestCluster cluster, HttpClient httpClient) throws Exception {
        testSslFailoverPattern(cluster, httpClient,
            worker -> worker.deployment().undeploy("demo.war"),
            "undeploy", true);
    }

    /**
     * Common SSL failover test pattern.
     * Verifies HTTPS requests work before and after worker failure.
     *
     * @param cluster Test cluster
     * @param httpClient HTTP client
     * @param failureAction Action to cause worker failure (shutdown, kill, undeploy)
     * @param actionName Name of the failure action for logging
     * @param configureElytron Whether to configure Elytron SSL
     * @throws Exception if test fails
     */
    private void testSslFailoverPattern(final TestCluster cluster, final HttpClient httpClient,
                                       final FailureAction failureAction, final String actionName,
                                       final boolean configureElytron) throws Exception {
        cluster.startWorkers(2);

        if (configureElytron) {
            final ElytronSSLConfigurator configurator = new ElytronSSLConfigurator();
            configurator.configureElytronSSL(cluster.getWorker1());
            configurator.configureElytronSSL(cluster.getWorker2());
        }

        final String httpsUrl = cluster.getBalancer().getHttpsUrl() + "/demo/";

        for (int iteration = 1; iteration <= 3; iteration++) {
            log.info("SSL failover via {} {} - iteration {}/3",
                     configureElytron ? "(Elytron)" : "", actionName, iteration);

            // Verify HTTPS works
            final HttpResponse initial = httpClient.getHttps(httpsUrl);
            final String worker = extractWorkerFromResponse(initial);

            softly.assertThat(initial.getStatusCode())
                .as("HTTPS connection should succeed")
                .isEqualTo(200);

            // Make several HTTPS requests
            for (int i = 0; i < 5; i++) {
                final HttpResponse response = httpClient.getHttps(httpsUrl);
                softly.assertThat(response.getStatusCode())
                    .as("HTTPS request %d should succeed", i)
                    .isEqualTo(200);
            }

            // Trigger worker failure
            final WildFlyContainer failedWorker = getWorkerByName(cluster, worker);
            log.info("Iteration {}: {} worker {}", iteration, actionName, worker);
            failureAction.execute(failedWorker);

            // Verify HTTPS failover
            await().atMost(ofSeconds(60))
                .pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    final HttpResponse response = httpClient.getHttps(httpsUrl);
                    assertThat(response.getStatusCode()).isEqualTo(200);
                });

            // Make additional HTTPS requests after failover
            for (int i = 0; i < 5; i++) {
                final HttpResponse response = httpClient.getHttps(httpsUrl);
                softly.assertThat(response.getStatusCode())
                    .as("Post-failover HTTPS request %d should succeed", i)
                    .isEqualTo(200);
            }

            log.info("Iteration {}: HTTPS failover completed", iteration);

            // Restore worker for next iteration
            if (iteration < 3) {
                if (actionName.equals("undeploy")) {
                    log.debug("Re-deploying demo.war to worker");
                    failedWorker.deployment().deployDemoApp();
                    // Wait for deployment and registration
                    await().atMost(ofSeconds(30))
                        .pollInterval(ofSeconds(2))
                        .untilAsserted(() -> {
                            assertThat(failedWorker.deployment().isDeployed("demo.war")).isTrue();
                        });
                } else {
                    log.debug("Restarting worker");
                    failedWorker.start();
                    if (configureElytron) {
                        new ElytronSSLConfigurator().configureElytronSSL(failedWorker);
                    }
                    await().atMost(ofSeconds(30))
                        .until(() -> cluster.getBalancer().getWorkerInfo().size() == 2);
                }
            }
        }

        log.info("SSL failover via {} {} completed successfully",
                 configureElytron ? "(Elytron)" : "", actionName);
    }

    /**
     * Functional interface for worker failure actions.
     */
    @FunctionalInterface
    private interface FailureAction {
        void execute(WildFlyContainer worker) throws Exception;
    }

    /**
     * Extracts worker name from HTTP response body.
     *
     * @param response HTTP response
     * @return Worker name (e.g., "worker1")
     */
    private String extractWorkerFromResponse(final HttpResponse response) {
        final String body = response.getBody();
        if (body.contains("worker1")) {
            return "worker1";
        }
        if (body.contains("worker2")) {
            return "worker2";
        }
        return "unknown";
    }

    /**
     * Gets WildFlyContainer by worker name.
     *
     * @param cluster Test cluster
     * @param workerName Worker name (e.g., "worker1")
     * @return WildFlyContainer for the named worker
     */
    private WildFlyContainer getWorkerByName(final TestCluster cluster, final String workerName) {
        switch (workerName) {
            case "worker1":
                return cluster.getWorker1();
            case "worker2":
                return cluster.getWorker2();
            default:
                throw new IllegalArgumentException("Unknown worker: " + workerName);
        }
    }
}
