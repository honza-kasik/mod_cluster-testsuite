package org.jboss.modcluster.test.context;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.dmr.ModelNode;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.HttpClient.HttpResponse;
import org.jboss.modcluster.test.utils.WildFlyContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Tests for context lifecycle management in mod_cluster.
 * Covers auto-enable, exclusions, disable/enable operations, and multiple contexts per worker.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class ContextLifecycleTest {

    private static final Logger log = LoggerFactory.getLogger(ContextLifecycleTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that contexts are automatically enabled when auto-enable-contexts is true.
     * Passes if demo.war context is automatically enabled after deployment without manual intervention.
     */
    @Test
    public void testAutoEnableContexts(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker = cluster.getWorker1();

        // Read auto-enable-contexts setting
        ModelNode autoEnable = worker.modCluster().readModClusterAttribute("auto-enable-contexts");
        log.info("auto-enable-contexts: {}", autoEnable);

        // The default should be true
        softly.assertThat(autoEnable.asBoolean())
                .as("auto-enable-contexts should be true by default")
                .isTrue();

        // Verify demo.war is deployed and accessible (auto-enabled)
        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo/";
        HttpResponse response = httpClient.get(balancerUrl);

        softly.assertThat(response.getStatusCode())
                .as("Auto-enabled context should be accessible")
                .isEqualTo(200);

        log.info("Context auto-enabled successfully");
    }

    /**
     * Verifies that excluded contexts are not registered with the balancer.
     * Passes if excluded-contexts configuration prevents context registration.
     */
    @Test
    public void testExcludedContextsNotRegistered(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker = cluster.getWorker1();

        // Read excluded-contexts configuration
        ModelNode excludedContexts = worker.modCluster().readModClusterAttribute("excluded-contexts");
        log.info("excluded-contexts: {}", excludedContexts);
        String originalValue = excludedContexts.isDefined() ? excludedContexts.asString() : "";

        // Verify demo is initially accessible via balancer
        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo/";
        HttpResponse initialResponse = httpClient.get(balancerUrl);
        softly.assertThat(initialResponse.getStatusCode())
                .as("Demo should be accessible before exclusion")
                .isEqualTo(200);

        // Set excluded-contexts to exclude demo context
        worker.modCluster().writeModClusterAttribute("excluded-contexts", "demo");

        // Reload worker to apply configuration
        worker.reload();

        // Wait for worker to restart
        Thread.sleep(5000);

        // Verify demo is NOT accessible via balancer after exclusion
        await().atMost(ofSeconds(15))
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode())
                            .as("Excluded context should return 404 or 503 via balancer")
                            .isIn(404, 503);
                });

        log.info("Excluded context verified as inaccessible via balancer");

        // Verify demo is STILL accessible directly to worker
        String directUrl = worker.getHttpUrl() + "/demo/";
        HttpResponse directResponse = httpClient.get(directUrl);
        softly.assertThat(directResponse.getStatusCode())
                .as("Excluded context should still be accessible directly")
                .isEqualTo(200);

        // Restore original value and reload
        if (originalValue.isEmpty()) {
            worker.modCluster().writeModClusterAttribute("excluded-contexts", ModelNode.fromString("undefined"));
        } else {
            worker.modCluster().writeModClusterAttribute("excluded-contexts", originalValue);
        }
        worker.reload();
        Thread.sleep(5000);

        // Verify demo is accessible again after removing exclusion
        await().atMost(ofSeconds(15))
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode())
                            .as("Context should be accessible again after removing exclusion")
                            .isEqualTo(200);
                });

        log.info("Excluded contexts configuration verified");
    }

    /**
     * Verifies that contexts can be dynamically disabled via management operations.
     * Passes if context becomes unavailable after disable operation.
     */
    @Test
    public void testDisableContext(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker = cluster.getWorker1();

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo/";

        // Verify context is initially accessible
        HttpResponse initialResponse = httpClient.get(balancerUrl);
        softly.assertThat(initialResponse.getStatusCode())
                .as("Context should be accessible initially")
                .isEqualTo(200);

        log.info("Context accessible, now invoking DISABLE-CONTEXT operation");

        // DISABLE the context using mod_cluster management operation
        worker.modCluster().disableContext("demo", "default-host");

        // Wait for disabled state to propagate to balancer
        await().atMost(ofSeconds(15))
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode())
                            .as("New requests to disabled context should fail")
                            .isIn(404, 503);
                });

        log.info("Context successfully disabled, verifying direct access still works");

        // Verify deployment still exists and is accessible directly
        String directUrl = worker.getHttpUrl() + "/demo/";
        HttpResponse directResponse = httpClient.get(directUrl);
        softly.assertThat(directResponse.getStatusCode())
                .as("Disabled context should still be accessible directly")
                .isEqualTo(200);

        // Re-enable the context
        log.info("Re-enabling context");
        worker.modCluster().enableContext("demo", "default-host");

        // Verify context is accessible again via balancer
        await().atMost(ofSeconds(15))
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode())
                            .as("Context should be accessible after re-enabling")
                            .isEqualTo(200);
                });

        log.info("Context disable/enable cycle verified successfully");
    }

    /**
     * Verifies that contexts can be stopped gracefully with proper timeout handling.
     * Passes if context stop operation completes within configured stop-context-timeout.
     */
    @Test
    public void testStopContext(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker = cluster.getWorker1();

        // Read stop-context-timeout configuration
        ModelNode stopTimeout = worker.modCluster().readModClusterAttribute("stop-context-timeout");
        log.info("stop-context-timeout: {}", stopTimeout);

        softly.assertThat(stopTimeout.asInt())
                .as("stop-context-timeout should be defined")
                .isGreaterThan(0);

        int timeoutSeconds = stopTimeout.asInt();
        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo/";

        // Verify context is accessible before stop
        HttpResponse response = httpClient.get(balancerUrl);
        softly.assertThat(response.getStatusCode())
                .as("Context should be accessible before stop")
                .isEqualTo(200);

        log.info("Invoking STOP-CONTEXT operation with timeout: {} seconds", timeoutSeconds);

        // Invoke STOP-CONTEXT operation
        worker.modCluster().stopContext("demo", "default-host");

        // Verify context becomes unavailable via balancer
        await().atMost(ofSeconds(timeoutSeconds + 10))
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse stopResponse = httpClient.get(balancerUrl);
                    assertThat(stopResponse.getStatusCode())
                            .as("Stopped context should return 404 or 503 via balancer")
                            .isIn(404, 503);
                });

        log.info("Context stopped successfully - verified inaccessible via balancer");

        // Note: Could also check logs for draining messages, but verifying
        // the context is actually stopped (unavailable) is the key behavior
    }

    /**
     * Verifies that multiple contexts can be deployed and accessed on a single worker.
     * Passes if both /demo and root context are accessible and load balanced.
     */
    /**
     * Verifies that multiple contexts can be deployed and accessed on a single worker.
     * Tests that contexts coexist independently and one context's lifecycle doesn't affect others.
     *
     * Passes if:
     * - All contexts are accessible via balancer
     * - All contexts are accessible directly on worker
     * - Undeploying one context doesn't affect others
     */
    @Test
    public void testMultipleContextsPerWorker(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker = cluster.getWorker1();

        // Define test contexts (using demo.war deployed with different names)
        final List<String> testContexts = Arrays.asList("app1.war", "app2.war", "app3.war");
        final File demoWar = new File("src/test/resources/deployments/demo.war");

        log.info("Deploying {} additional contexts to test multiple contexts per worker", testContexts.size());

        // Deploy multiple instances of demo.war with different names
        for (String contextName : testContexts) {
            worker.deployment().deploy(demoWar, contextName);
            log.info("Deployed context: {}", contextName);
        }

        // Wait for contexts to register with mod_cluster
        Thread.sleep(3000);

        // Verify original demo context still works
        String demoBalancerUrl = cluster.getBalancer().getHttpUrl() + "/demo/";
        HttpResponse demoResponse = httpClient.get(demoBalancerUrl);
        softly.assertThat(demoResponse.getStatusCode())
                .as("Original demo context should be accessible via balancer")
                .isEqualTo(200);

        // Verify all new contexts are accessible via balancer
        log.info("Verifying all contexts accessible via balancer");
        for (String contextName : testContexts) {
            String contextPath = contextName.replace(".war", "");
            String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + contextPath + "/";

            await().atMost(ofSeconds(15))
                    .pollInterval(ofSeconds(2))
                    .untilAsserted(() -> {
                        HttpResponse response = httpClient.get(balancerUrl);
                        assertThat(response.getStatusCode())
                                .as("Context %s should be accessible via balancer", contextPath)
                                .isEqualTo(200);
                    });

            log.info("Context {} accessible via balancer", contextPath);
        }

        // Verify all contexts are accessible directly on worker
        log.info("Verifying all contexts accessible directly on worker");
        for (String contextName : testContexts) {
            String contextPath = contextName.replace(".war", "");
            String directUrl = worker.getHttpUrl() + "/" + contextPath + "/";

            HttpResponse directResponse = httpClient.get(directUrl);
            softly.assertThat(directResponse.getStatusCode())
                    .as("Context %s should be accessible directly on worker", contextPath)
                    .isEqualTo(200);

            log.info("Context {} accessible directly on worker", contextPath);
        }

        // Test context independence: undeploy one context
        String testContext = testContexts.get(0); // "app1.war"
        String testContextPath = testContext.replace(".war", "");
        log.info("Testing context independence: undeploying {}", testContext);

        worker.deployment().undeploy(testContext);

        // Verify undeployed context is no longer accessible
        await().atMost(ofSeconds(15))
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(
                        cluster.getBalancer().getHttpUrl() + "/" + testContextPath + "/"
                    );
                    assertThat(response.getStatusCode())
                            .as("Undeployed context should return 404 or 503")
                            .isIn(404, 503);
                });

        log.info("Undeployed context {} no longer accessible", testContextPath);

        // Verify other contexts still work
        log.info("Verifying other contexts still accessible after undeploying one");
        List<String> remainingContexts = testContexts.subList(1, testContexts.size());

        for (String contextName : remainingContexts) {
            String contextPath = contextName.replace(".war", "");
            String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + contextPath + "/";

            HttpResponse response = httpClient.get(balancerUrl);
            softly.assertThat(response.getStatusCode())
                    .as("Context %s should still be accessible after undeploying %s",
                        contextPath, testContextPath)
                    .isEqualTo(200);

            log.info("Context {} still accessible", contextPath);
        }

        // Cleanup: undeploy remaining test contexts
        log.info("Cleaning up remaining test contexts");
        for (String contextName : remainingContexts) {
            try {
                worker.deployment().undeploy(contextName);
                log.info("Undeployed {}", contextName);
            } catch (Exception e) {
                log.warn("Failed to undeploy {} during cleanup: {}", contextName, e.getMessage());
            }
        }

        log.info("Multiple contexts per worker verified successfully");
    }

    /**
     * Verifies that contexts can be redeployed and automatically re-register with the balancer.
     * Passes if context becomes unavailable during redeployment and accessible again after completion.
     */
    @Test
    public void testContextRedeployment(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);
        WildFlyContainer worker = cluster.getWorker1();

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo/";

        // Verify initial deployment is accessible
        HttpResponse initialResponse = httpClient.get(balancerUrl);
        softly.assertThat(initialResponse.getStatusCode())
                .as("Context should be accessible before redeployment")
                .isEqualTo(200);

        log.info("Initial deployment verified, now undeploying demo.war");

        // UNDEPLOY the application
        worker.deployment().undeploy("demo.war");

        // Wait for context to unregister from balancer
        await().atMost(ofSeconds(20))
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode())
                            .as("Undeployed context should return 404 via balancer")
                            .isIn(404, 503);
                });

        log.info("Context unregistered from balancer after undeploy");

        // Verify deployment no longer exists
        boolean isDeployed = worker.deployment().isDeployed("demo.war");
        softly.assertThat(isDeployed)
                .as("demo.war should not be deployed after undeploy")
                .isFalse();

        log.info("Redeploying demo.war");

        // REDEPLOY the application
        worker.deployment().deployDemoApp();

        // Wait for context to re-register with balancer
        await().atMost(ofSeconds(20))
                .pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(balancerUrl);
                    assertThat(response.getStatusCode())
                            .as("Redeployed context should be accessible via balancer")
                            .isEqualTo(200);
                });

        log.info("Context re-registered with balancer after redeploy");

        // Verify deployment exists again
        boolean isRedeployed = worker.deployment().isDeployed("demo.war");
        softly.assertThat(isRedeployed)
                .as("demo.war should be deployed after redeploy")
                .isTrue();

        log.info("Context redeployment cycle verified successfully");
    }
}
