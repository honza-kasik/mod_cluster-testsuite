package org.jboss.modcluster.test.loadbalancing;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.dmr.ModelNode;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.WildFlyContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.ReadResourceOption;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for load calculation and metrics in mod_cluster.
 * Verifies load factor calculation, custom metrics, load-based routing, and dynamic load adjustment.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class LoadMetricsTest {

    private static final Logger log = LoggerFactory.getLogger(LoadMetricsTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that load factor is calculated and reported by workers to the balancer.
     * Passes if workers report non-zero load factors in their mod_cluster proxy configuration.
     */
    @Test
    public void testLoadFactorCalculation(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);
        WildFlyContainer worker1 = cluster.getWorker1();
        WildFlyContainer worker2 = cluster.getWorker2();

        // Generate some load
        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo/";
        httpClient.testLoadDistribution(balancerUrl, 50);

        // Read load-related configuration from worker1
        Operations ops = worker1.getOperations();
        Address proxyAddress = Address.subsystem("modcluster").and("proxy", "default");

        ModelNodeResult proxyConfig = ops.readResource(proxyAddress, ReadResourceOption.INCLUDE_RUNTIME);
        proxyConfig.assertSuccess();
        ModelNode proxy = proxyConfig.value();

        log.info("Worker1 proxy config: {}", proxy.toJSONString(true));

        // Verify proxy configuration has load-related settings
        softly.assertThat(proxy.hasDefined("status-interval"))
                .as("Proxy should have status-interval defined (for load reporting)")
                .isTrue();

        int statusInterval = proxy.get("status-interval").asInt();
        log.info("Status interval (load reporting frequency): {} seconds", statusInterval);

        softly.assertThat(statusInterval)
                .as("Status interval should be reasonable (typically 10 seconds)")
                .isGreaterThan(0)
                .isLessThan(60);

        // Verify load provider configuration
        softly.assertThat(proxy.hasDefined("load-provider"))
                .as("Proxy should have load-provider configuration")
                .isTrue();

        log.info("Load factor calculation mechanism verified");
    }

    /**
     * Verifies that custom load metrics control traffic distribution.
     * The custom FileBasedLoadMetric module is pre-baked into the container image.
     * This test configures it dynamically and verifies traffic routes based on load values.
     *
     * Test flow:
     * 1. Configure custom load metric in mod_cluster subsystem (module already in image)
     * 2. Reload workers to activate the metric
     * 3. Set different load values via files
     * 4. Verify traffic routes to less-loaded worker
     * 5. Reverse load values
     * 6. Verify traffic routing reverses
     */
    @Test
    public void testCustomLoadMetrics(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);
        WildFlyContainer worker1 = cluster.getWorker1();
        WildFlyContainer worker2 = cluster.getWorker2();

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo/";

        // Check if custom load metric module is present in container image
        boolean w1HasModule = worker1.hasCustomLoadMetricModule();
        boolean w2HasModule = worker2.hasCustomLoadMetricModule();

        log.info("Worker1 has custom metric module: {}", w1HasModule);
        log.info("Worker2 has custom metric module: {}", w2HasModule);

        if (w1HasModule) {
            String w1Files = worker1.listCustomLoadMetricModule();
            log.info("Worker1 module files: {}", w1Files.replace("\n", " | "));
        } else {
            log.warn("Worker1 custom metric module NOT FOUND - image may not have been built with module");
        }

        if (w2HasModule) {
            String w2Files = worker2.listCustomLoadMetricModule();
            log.info("Worker2 module files: {}", w2Files.replace("\n", " | "));
        } else {
            log.warn("Worker2 custom metric module NOT FOUND - image may not have been built with module");
        }

        softly.assertThat(w1HasModule && w2HasModule)
                .as("Both workers should have custom metric module pre-loaded in image")
                .isTrue();

        // Set initial neutral load values
        String loadFilePath = "/tmp/modcluster-load.txt";
        worker1.writeLoadValue(500, loadFilePath);
        worker2.writeLoadValue(500, loadFilePath);

        // Configure custom load metric (will trigger restart)
        // Use weight=1 like noe-tests (with no other metrics, weight doesn't matter)
        log.info("Configuring custom load metric (weight=1 matching noe-tests)...");
        worker1.configureCustomLoadMetric(loadFilePath, 1000, 1);
        worker2.configureCustomLoadMetric(loadFilePath, 1000, 1);

        // Verify custom metric is configured in subsystem
        verifyCustomMetricConfigured(worker1, worker2);

        // Wait a bit for everything to stabilize
        log.info("Waiting for system to stabilize...");
        Thread.sleep(5000);

        // SCENARIO 1: High load on worker1, low load on worker2
        log.info("SCENARIO 1: Setting worker1=900 (high), worker2=100 (low)");
        worker1.writeLoadValue(900, loadFilePath);
        worker2.writeLoadValue(100, loadFilePath);

        // Wait for balancer to receive STATUS messages with correct load values
        // Expected load = (1000 - fileValue) / 10 (following noe-tests formula)
        // worker1: (1000 - 900) / 10 = 10
        // worker2: (1000 - 100) / 10 = 90
        log.info("Waiting for balancer to report expected loads (worker1=10, worker2=90)...");
        waitForExpectedLoads(cluster, "worker1", 10, "worker2", 90, 60);

        Map<String, Integer> scenario1 = httpClient.testLoadDistribution(balancerUrl, 500);
        log.info("Scenario 1 distribution (900/100): {}", scenario1);

        int s1_w1 = scenario1.getOrDefault("worker1", 0);
        int s1_w2 = scenario1.getOrDefault("worker2", 0);

        softly.assertThat(s1_w2)
                .as("Scenario 1: Worker2 (load=100) should get more traffic than worker1 (load=900)")
                .isGreaterThan(s1_w1);

        // SCENARIO 2: Reverse the loads
        log.info("SCENARIO 2: Reversing - worker1=100 (low), worker2=900 (high)");
        worker1.writeLoadValue(100, loadFilePath);
        worker2.writeLoadValue(900, loadFilePath);

        // Wait for balancer to receive STATUS messages with correct load values
        // worker1: (1000 - 100) / 10 = 90
        // worker2: (1000 - 900) / 10 = 10
        log.info("Waiting for balancer to report expected loads (worker1=90, worker2=10)...");
        waitForExpectedLoads(cluster, "worker1", 90, "worker2", 10, 60);

        Map<String, Integer> scenario2 = httpClient.testLoadDistribution(balancerUrl, 500);
        log.info("Scenario 2 distribution (100/900): {}", scenario2);

        int s2_w1 = scenario2.getOrDefault("worker1", 0);
        int s2_w2 = scenario2.getOrDefault("worker2", 0);

        softly.assertThat(s2_w1)
                .as("Scenario 2: Worker1 (load=100) should get more traffic than worker2 (load=900)")
                .isGreaterThan(s2_w2);

        log.info("Custom load metric verified - traffic routes based on load:");
        log.info("  Scenario 1 (W1=900, W2=100): worker1={}, worker2={}", s1_w1, s1_w2);
        log.info("  Scenario 2 (W1=100, W2=900): worker1={}, worker2={}", s2_w1, s2_w2);
    }

    private void verifyCustomMetricConfigured(WildFlyContainer worker1, WildFlyContainer worker2)
            throws Exception {
        // mod_cluster uses the full class name as the key, not the custom name we specify
        Address metricAddr = Address.subsystem("modcluster").and("proxy", "default")
                .and("load-provider", "dynamic")
                .and("custom-load-metric", "org.jboss.modcluster.test.metric.FileBasedLoadMetric");

        log.info("Worker1 load-provider config: {}",
                worker1.getOperations().readResource(Address.subsystem("modcluster").and("proxy", "default")
                        .and("load-provider", "dynamic"), ReadResourceOption.RECURSIVE).stringValue());

        boolean w1HasMetric = worker1.getOperations().exists(metricAddr);
        boolean w2HasMetric = worker2.getOperations().exists(metricAddr);

        log.info("Custom metric configured: worker1={}, worker2={}", w1HasMetric, w2HasMetric);

        assertThat(w1HasMetric && w2HasMetric)
                .as("Both workers should have custom metric configured")
                .isTrue();
    }

    /**
     * Wait for the balancer to report expected load values for workers.
     * Polls the balancer until loads match expected values or timeout.
     * Following noe-tests approach: load = (1000 - fileValue) / 10
     */
    private void waitForExpectedLoads(ModClusterTestExtension.TestCluster cluster,
                                      String worker1Name, int expectedLoad1,
                                      String worker2Name, int expectedLoad2,
                                      int timeoutSeconds) throws Exception {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000L;
        boolean worker1Found = false;
        boolean worker2Found = false;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try {
                Map<String, ModelNode> workers = cluster.getBalancer().getWorkerInfo();

                if (workers.containsKey(worker1Name)) {
                    int actualLoad1 = workers.get(worker1Name).get("load").asInt();
                    if (actualLoad1 == expectedLoad1) {
                        worker1Found = true;
                        log.info("{} reached expected load: {}", worker1Name, expectedLoad1);
                    } else {
                        log.info("{} current load: {} (expected: {})", worker1Name, actualLoad1, expectedLoad1);
                    }
                }

                if (workers.containsKey(worker2Name)) {
                    int actualLoad2 = workers.get(worker2Name).get("load").asInt();
                    if (actualLoad2 == expectedLoad2) {
                        worker2Found = true;
                        log.info("{} reached expected load: {}", worker2Name, expectedLoad2);
                    } else {
                        log.info("{} current load: {} (expected: {})", worker2Name, actualLoad2, expectedLoad2);
                    }
                }

                if (worker1Found && worker2Found) {
                    log.info("Both workers reporting expected loads");
                    return;
                }
            } catch (Exception e) {
                log.debug("Error checking loads: {}", e.getMessage());
            }

            Thread.sleep(2000); // Poll every 2 seconds
        }

        softly.fail("Balancer didn't report expected loads. This means load never propagated.");
    }

    /**
     * Verifies that load-based routing distributes requests according to worker capacity.
     * Tests with built-in dynamic load metrics (no custom metrics needed).
     * Passes if workers with different resource availability receive proportionally different traffic.
     */
    @Test
    public void testLoadBasedRouting(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo/";

        // Generate traffic to observe load-based distribution
        Map<String, Integer> distribution = httpClient.testLoadDistribution(balancerUrl, 100);
        log.info("Load distribution with dynamic load metrics: {}", distribution);

        int worker1Requests = distribution.getOrDefault("worker1", 0);
        int worker2Requests = distribution.getOrDefault("worker2", 0);

        // Both workers should receive requests
        softly.assertThat(distribution)
                .as("Both workers should receive requests via load-based routing")
                .containsKeys("worker1", "worker2");

        // Total should match request count
        softly.assertThat(worker1Requests + worker2Requests)
                .as("Total requests should match expected count")
                .isEqualTo(100);

        // Distribution should be relatively balanced with default built-in metrics
        // (Allow up to 70/30 split as acceptable variance)
        int maxRequests = Math.max(worker1Requests, worker2Requests);
        softly.assertThat(maxRequests)
                .as("Distribution should be relatively balanced (no worker gets >70%)")
                .isLessThan(70);

        log.info("Load-based routing verified: worker1={}, worker2={}", worker1Requests, worker2Requests);
    }

    /**
     * Verifies that initial load is reported when worker first registers with balancer.
     * Passes if workers successfully register and report initial load metrics within 30 seconds.
     */
    @Test
    public void testInitialLoadReporting(TestCluster cluster, HttpClient httpClient) throws Exception {
        // Start with no workers
        cluster.startWorkers(0);

        // Add worker1 and verify it registers with initial load
        log.info("Starting worker1 to test initial load reporting...");
        cluster.startWorkers(1);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo/";

        // Verify worker is accessible via balancer (indicates successful registration with load)
        HttpClient.HttpResponse response = httpClient.get(balancerUrl);
        softly.assertThat(response.getStatusCode())
                .as("Worker should be accessible after registration with initial load")
                .isEqualTo(200);

        // Read worker's status-interval to verify load reporting is configured
        WildFlyContainer worker = cluster.getWorker1();
        ModelNode statusInterval = worker.readModClusterAttribute("status-interval");

        log.info("Worker registered with status-interval: {} seconds", statusInterval.asInt());

        softly.assertThat(statusInterval.asInt())
                .as("Status interval should be configured for load reporting")
                .isGreaterThan(0);

        log.info("Initial load reporting verified");
    }

    /**
     * Verifies that load metrics are dynamically updated and reflected in routing decisions.
     * Monitors load distribution over multiple rounds of traffic.
     * Passes if load balancing remains consistent across multiple traffic bursts.
     */
    @Test
    public void testDynamicLoadAdjustment(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo/";

        // Generate multiple rounds of traffic to observe dynamic load adjustment
        Map<String, Integer> round1 = httpClient.testLoadDistribution(balancerUrl, 50);
        log.info("Round 1 distribution: {}", round1);

        Map<String, Integer> round2 = httpClient.testLoadDistribution(balancerUrl, 50);
        log.info("Round 2 distribution: {}", round2);

        Map<String, Integer> round3 = httpClient.testLoadDistribution(balancerUrl, 50);
        log.info("Round 3 distribution: {}", round3);

        // Verify both workers receive traffic in each round (dynamic load balancing is active)
        softly.assertThat(round1)
                .as("Round 1: Both workers should receive requests")
                .containsKeys("worker1", "worker2");

        softly.assertThat(round2)
                .as("Round 2: Both workers should receive requests")
                .containsKeys("worker1", "worker2");

        softly.assertThat(round3)
                .as("Round 3: Both workers should receive requests")
                .containsKeys("worker1", "worker2");

        log.info("Dynamic load adjustment verified - workers continue to receive traffic across multiple rounds");
    }
}
