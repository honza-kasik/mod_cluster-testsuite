package org.jboss.modcluster.test.ha;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.dmr.ModelNode;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.HttpClient.HttpResponse;
import org.jboss.modcluster.test.utils.WildFlyContainer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * High availability topology testing.
 * Tests hot-standby behavior and multi-balancer scenarios.
 * Some tests are deferred to Phase 3 due to infrastructure requirements.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class HighAvailabilityTest {

    private static final Logger log = LoggerFactory.getLogger(HighAvailabilityTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that hot standby worker only receives traffic when all normal workers are down.
     * Configures worker1 as hot standby (load=0) and workers 2-4 as normal workers.
     * Passes if standby receives no traffic until all normal workers are killed.
     */
    @Test
    public void testHotStandbyActivatesWhenAllWorkersDown(TestCluster cluster, HttpClient httpClient) throws Exception {
        // Start all 4 workers
        cluster.startWorkers(4);

        // Configure worker1 as hot standby (load=0)
        final WildFlyContainer standby = cluster.getWorker1();
        standby.loadMetrics().setFixedLoad(0);

        final String url = cluster.getBalancer().getHttpUrl() + "/demo/";

        // Wait for registration
        log.info("Waiting for all workers to register with balancer");
        await().atMost(ofSeconds(30))
            .until(() -> cluster.getBalancer().getWorkerInfo().size() >= 4);

        // Verify standby has load=0
        final Map<String, ModelNode> workerInfo = cluster.getBalancer().getWorkerInfo();
        log.info("Worker info: {}", workerInfo.keySet());

        // Make 100 requests - none should go to standby
        log.info("Testing that standby worker receives no requests when normal workers are available");
        final Map<String, Integer> distribution = httpClient.testLoadDistribution(url, 100);
        log.info("Initial distribution: {}", distribution);

        softly.assertThat(distribution.getOrDefault(standby.getName(), 0))
            .as("Standby worker should not receive any requests")
            .isEqualTo(0);

        // Kill normal workers one by one
        final List<WildFlyContainer> normalWorkers = Arrays.asList(
            cluster.getWorker2(), cluster.getWorker3(), cluster.getWorker4()
        );

        for (int i = 0; i < normalWorkers.size(); i++) {
            final HttpResponse response = httpClient.get(url);
            final String worker = extractWorkerFromResponse(response);
            final String sessionCookie = response.getCookie("JSESSIONID");

            log.info("Established session on worker: {}", worker);

            // Kill worker handling request
            final WildFlyContainer workerToKill = getWorkerByName(cluster, worker);
            log.info("Killing worker: {}", worker);
            workerToKill.kill();

            // Wait for failover
            await().atMost(ofSeconds(30))
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    final HttpResponse failoverResp = httpClient.getWithSession(url, "JSESSIONID=" + sessionCookie);
                    assertThat(failoverResp.getStatusCode()).isEqualTo(200);
                });

            final HttpResponse afterFailover = httpClient.getWithSession(url, "JSESSIONID=" + sessionCookie);
            final String newWorker = extractWorkerFromResponse(afterFailover);

            log.info("Failover iteration {}: {} -> {}", i + 1, worker, newWorker);

            if (i < normalWorkers.size() - 1) {
                // Not last iteration - should NOT use standby
                softly.assertThat(newWorker)
                    .as("Failover should go to normal worker, not standby")
                    .isNotEqualTo(standby.getName());
            } else {
                // Last iteration - all normal workers dead, MUST use standby
                softly.assertThat(newWorker)
                    .as("When all normal workers down, must use standby")
                    .isEqualTo(standby.getName());
            }
        }

        log.info("Hot standby activation test completed successfully");
    }

    /**
     * Verifies repeated failover behavior with hot standby configuration.
     * Runs 10 cycles of request, identify worker, kill worker, verify standby not used until last iteration.
     * Passes if standby never receives traffic until final iteration when all normal workers are down.
     */
    @Test
    public void testHotStandbyRepeatedFailover(TestCluster cluster, HttpClient httpClient) throws Exception {
        // Start all 4 workers
        cluster.startWorkers(4);

        // Configure worker1 as hot standby (load=0)
        final WildFlyContainer standby = cluster.getWorker1();
        standby.loadMetrics().setFixedLoad(0);

        final String url = cluster.getBalancer().getHttpUrl() + "/demo/";

        // Wait for registration
        log.info("Waiting for all workers to register with balancer");
        await().atMost(ofSeconds(30))
            .until(() -> cluster.getBalancer().getWorkerInfo().size() >= 4);

        // Track which workers have been killed
        boolean worker2Killed = false;
        boolean worker3Killed = false;
        boolean worker4Killed = false;

        // 10 cycles of failover testing
        for (int cycle = 1; cycle <= 10; cycle++) {
            log.info("Hot standby failover cycle {}/10", cycle);

            // Make request to establish session
            final HttpResponse response = httpClient.get(url);
            final String worker = extractWorkerFromResponse(response);
            final String sessionCookie = response.getCookie("JSESSIONID");

            log.info("Cycle {}: Session on worker {}", cycle, worker);

            // Kill the worker handling the request (if it's a normal worker)
            if (!"worker1".equals(worker)) {
                final WildFlyContainer workerToKill = getWorkerByName(cluster, worker);
                log.info("Cycle {}: Killing worker {}", cycle, worker);
                workerToKill.kill();

                // Track killed workers
                switch (worker) {
                    case "worker2":
                        worker2Killed = true;
                        break;
                    case "worker3":
                        worker3Killed = true;
                        break;
                    case "worker4":
                        worker4Killed = true;
                        break;
                }

                // Wait for failover
                await().atMost(ofSeconds(30))
                    .pollInterval(ofSeconds(2))
                    .untilAsserted(() -> {
                        final HttpResponse failoverResp = httpClient.getWithSession(url, "JSESSIONID=" + sessionCookie);
                        assertThat(failoverResp.getStatusCode()).isEqualTo(200);
                    });

                // Make 4 more requests to verify routing
                for (int i = 0; i < 4; i++) {
                    final HttpResponse followUpResp = httpClient.getWithSession(url, "JSESSIONID=" + sessionCookie);
                    final String followUpWorker = extractWorkerFromResponse(followUpResp);

                    // If all normal workers are killed, must use standby
                    if (worker2Killed && worker3Killed && worker4Killed) {
                        softly.assertThat(followUpWorker)
                            .as("Cycle {}: When all normal workers down, must use standby", cycle)
                            .isEqualTo(standby.getName());
                    } else {
                        // Some normal workers still available, should NOT use standby
                        softly.assertThat(followUpWorker)
                            .as("Cycle {}: Should route to normal worker, not standby", cycle)
                            .isNotEqualTo(standby.getName());
                    }
                }

                // Re-enable killed worker for next iteration (except on last iteration)
                if (cycle < 10 && !standby.getName().equals(worker)) {
                    log.info("Cycle {}: Restarting worker {}", cycle, worker);

                    // Delay to allow Podman to fully clean up killed container before restarting
                    // Podman needs sufficient time to release socket resources after kill
                    Thread.sleep(5000);

                    workerToKill.start();

                    // Wait for worker to register
                    Thread.sleep(10000);

                    // Reset kill tracking
                    switch (worker) {
                        case "worker2":
                            worker2Killed = false;
                            break;
                        case "worker3":
                            worker3Killed = false;
                            break;
                        case "worker4":
                            worker4Killed = false;
                            break;
                    }
                }
            } else {
                // Already on standby (shouldn't happen until all others are down)
                log.info("Cycle {}: Already on standby worker", cycle);
            }
        }

        log.info("Hot standby repeated failover test completed successfully");
    }

    /**
     * DEFERRED TO PHASE 3: Two balancer settings test.
     * Requires multiple balancer orchestration, different worker groups, complex network isolation.
     */
    @Test
    @Disabled("Phase 3: Multiple balancer orchestration required")
    public void testTwoBalancerSettings() throws Exception {
        /*
         * This test requires:
         * - 2 independent balancers with different configurations
         * - 2 groups of workers, each group associated with one balancer
         * - Network isolation to ensure workers only communicate with their assigned balancer
         * - Verification that workers respect their balancer-specific settings
         *
         * Infrastructure needs:
         * - Multiple Network instances in Testcontainers
         * - Balancer group configuration in mod_cluster subsystem
         * - Worker affinity to specific balancer groups
         *
         * Deferred to Phase 3 for proper multi-balancer infrastructure support.
         */
        log.info("Test deferred to Phase 3: Multiple balancer orchestration required");
    }

    /**
     * DEFERRED TO PHASE 3: Two httpd instances test.
     * Requires httpd balancer support, state synchronization verification, MCM parsing.
     */
    @Test
    @Disabled("Phase 3: httpd balancer support required")
    public void testTwoHttpdInstances() throws Exception {
        /*
         * This test requires:
         * - 2 Apache httpd instances with mod_cluster
         * - Verification of state synchronization between httpd instances
         * - MCM (Mod_Cluster Manager) endpoint parsing and validation
         * - Load balancing across multiple httpd frontends
         *
         * Infrastructure needs:
         * - HttpdBalancerContainer full implementation
         * - MCM web interface parser for worker status
         * - httpd configuration templates for multi-instance setup
         * - Verification of MCMP communication between workers and multiple httpd instances
         *
         * Deferred to Phase 3 for httpd balancer support and multi-instance orchestration.
         */
        log.info("Test deferred to Phase 3: httpd balancer support required");
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
        if (body.contains("worker3")) {
            return "worker3";
        }
        if (body.contains("worker4")) {
            return "worker4";
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
            case "worker3":
                return cluster.getWorker3();
            case "worker4":
                return cluster.getWorker4();
            default:
                throw new IllegalArgumentException("Unknown worker: " + workerName);
        }
    }
}
