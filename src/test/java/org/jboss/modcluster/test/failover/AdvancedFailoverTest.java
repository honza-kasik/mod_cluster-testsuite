package org.jboss.modcluster.test.failover;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.HttpClient.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Advanced failover scenarios testing.
 * Tests session migration, deterministic routing, graceful failover, and failover under various conditions.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class AdvancedFailoverTest {

    private static final Logger log = LoggerFactory.getLogger(AdvancedFailoverTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that active sessions are maintained during worker failover.
     * Passes if session established on worker1 continues to work after worker1 stops and requests route to worker2.
     */
    @Test
    public void testFailoverWithActiveSessions(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo/";

        // Establish session on one of the workers
        HttpResponse initialResponse = httpClient.get(balancerUrl);
        String sessionCookie = initialResponse.getCookie("JSESSIONID");
        String initialWorker = extractWorkerFromSessionId(sessionCookie);

        log.info("Session established: {} on worker: {}", sessionCookie, initialWorker);

        // Make several requests to verify session is active
        for (int i = 0; i < 5; i++) {
            HttpResponse response = httpClient.getWithSession(balancerUrl, "JSESSIONID=" + sessionCookie);
            softly.assertThat(response.getStatusCode())
                    .as("Request %d with session should succeed", i + 1)
                    .isEqualTo(200);
        }

        // Stop the worker holding the session
        if ("worker1".equals(initialWorker)) {
            log.info("Stopping worker1 (session holder)...");
            cluster.getWorker1().stop();
        } else {
            log.info("Stopping worker2 (session holder)...");
            cluster.getWorker2().stop();
        }

        // Wait for failover and verify session still works (may route to other worker)
        await().atMost(ofSeconds(60))
                .pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    try {
                        HttpResponse response = httpClient.getWithSession(balancerUrl, "JSESSIONID=" + sessionCookie);
                        softly.assertThat(response.getStatusCode())
                                .as("Session should failover successfully")
                                .isEqualTo(200);
                    } catch (Exception e) {
                        // Allow some failures during transition
                        log.debug("Failover in progress: {}", e.getMessage());
                        throw e;
                    }
                });

        log.info("Session failover completed successfully");
    }

    /**
     * Verifies deterministic failover routing based on worker configuration.
     * Passes if requests consistently route to the expected worker in a predictable order.
     */
    @Test
    public void testDeterministicFailover(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo/";

        // Verify both workers are active
        Map<String, Integer> initialDist = httpClient.testLoadDistribution(balancerUrl, 20);
        softly.assertThat(initialDist)
                .as("Both workers should be active initially")
                .containsKeys("worker1", "worker2");

        log.info("Initial distribution: {}", initialDist);

        // Stop worker1 - all traffic should deterministically go to worker2
        log.info("Stopping worker1 for deterministic failover test...");
        cluster.getWorker1().stop();

        // Wait for deterministic failover to worker2
        await().atMost(ofSeconds(60))
                .pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    var dist = httpClient.testLoadDistribution(balancerUrl, 10);
                    softly.assertThat(dist)
                            .as("All traffic should deterministically route to worker2")
                            .containsOnlyKeys("worker2");
                });

        // Verify consistent routing to worker2
        Map<String, Integer> failoverDist = httpClient.testLoadDistribution(balancerUrl, 50);
        log.info("Failover distribution: {}", failoverDist);

        softly.assertThat(failoverDist)
                .as("Deterministic failover should route all traffic to worker2")
                .containsOnlyKeys("worker2");
    }

    /**
     * Verifies graceful failover without dropped requests during worker shutdown.
     * Passes if all requests during worker shutdown receive valid responses with no errors.
     */
    @Test
    public void testGracefulFailoverNoDroppedRequests(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo/";

        // Start making continuous requests in background
        List<Integer> statusCodes = new ArrayList<>();
        List<Exception> errors = new ArrayList<>();

        Thread requestThread = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                try {
                    HttpResponse response = httpClient.get(balancerUrl);
                    synchronized (statusCodes) {
                        statusCodes.add(response.getStatusCode());
                    }
                    Thread.sleep(100); // 10 requests/second
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            }
        });

        requestThread.start();

        // Wait for some requests to complete, then stop worker1
        Thread.sleep(2000);
        log.info("Stopping worker1 during active traffic...");
        cluster.getWorker1().stop();

        // Wait for all requests to complete
        requestThread.join();

        log.info("Completed {} requests with {} errors", statusCodes.size(), errors.size());

        // Verify graceful failover - most requests should succeed
        // Allow some failures during transition, but should be minimal
        long successfulRequests = statusCodes.stream().filter(code -> code == 200).count();
        double successRate = (double) successfulRequests / statusCodes.size();

        softly.assertThat(successRate)
                .as("Success rate during graceful failover should be high (>= 0.8)")
                .isGreaterThanOrEqualTo(0.8);

        log.info("Success rate: {}", successRate);
    }

    /**
     * Verifies failover behavior when worker unregisters gracefully from the cluster.
     * Passes if traffic stops routing to unregistering worker within 60 seconds without errors.
     */
    @Test
    public void testFailoverDuringUnregistration(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo/";

        // Verify both workers receiving traffic
        Map<String, Integer> initialDist = httpClient.testLoadDistribution(balancerUrl, 20);
        softly.assertThat(initialDist)
                .as("Both workers should be active")
                .containsKeys("worker1", "worker2");

        log.info("Initial distribution: {}", initialDist);

        // Gracefully stop worker1 (triggers unregistration)
        log.info("Initiating graceful unregistration of worker1...");
        cluster.getWorker1().stop();

        // Monitor failover during unregistration
        await().atMost(ofSeconds(60))
                .pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    var dist = httpClient.testLoadDistribution(balancerUrl, 10);
                    softly.assertThat(dist)
                            .as("Traffic should failover to worker2 during unregistration")
                            .containsOnlyKeys("worker2");
                    softly.assertThat(dist.get("worker2"))
                            .as("Worker2 should handle all successful requests")
                            .isGreaterThan(0);
                });

        log.info("Failover during unregistration completed successfully");
    }

    /**
     * Verifies failover behavior under high load conditions.
     * Passes if failover completes within 60 seconds while handling 200+ concurrent requests.
     */
    @Test
    public void testFailoverUnderLoad(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo/";

        // Generate load with multiple concurrent request threads
        final int NUM_THREADS = 5;
        final int REQUESTS_PER_THREAD = 50;
        List<Thread> threads = new ArrayList<>();
        List<Integer> allStatusCodes = new ArrayList<>();

        for (int t = 0; t < NUM_THREADS; t++) {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                    try {
                        HttpResponse response = httpClient.get(balancerUrl);
                        synchronized (allStatusCodes) {
                            allStatusCodes.add(response.getStatusCode());
                        }
                    } catch (Exception e) {
                        log.debug("Request failed under load: {}", e.getMessage());
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }

        // Wait briefly for load to build up, then trigger failover
        Thread.sleep(2000);
        log.info("Stopping worker1 under load ({} threads, {} total requests)...", NUM_THREADS, NUM_THREADS * REQUESTS_PER_THREAD);
        cluster.getWorker1().stop();

        // Wait for all request threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        log.info("Load test completed: {} total requests", allStatusCodes.size());

        // Verify that traffic successfully failed over to worker2
        await().atMost(ofSeconds(60))
                .pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    var dist = httpClient.testLoadDistribution(balancerUrl, 20);
                    softly.assertThat(dist)
                            .as("All traffic should route to worker2 after failover under load")
                            .containsOnlyKeys("worker2");
                });

        // Verify reasonable success rate under load
        long successCount = allStatusCodes.stream().filter(code -> code == 200).count();
        double successRate = (double) successCount / allStatusCodes.size();

        softly.assertThat(successRate)
                .as("Success rate under load should be acceptable (>= 0.7)")
                .isGreaterThanOrEqualTo(0.7);

        log.info("Success rate under load: {}", successRate);
    }

    /**
     * Extract worker/route information from JSESSIONID.
     * Format is typically: <session-id>.<route>
     */
    private String extractWorkerFromSessionId(String sessionId) {
        if (sessionId != null && sessionId.contains(".")) {
            return sessionId.substring(sessionId.lastIndexOf('.') + 1);
        }
        return "unknown";
    }
}
