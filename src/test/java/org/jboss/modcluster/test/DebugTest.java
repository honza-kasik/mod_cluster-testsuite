package org.jboss.modcluster.test;

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

@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class DebugTest {

    private static final Logger log = LoggerFactory.getLogger(DebugTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    @Test
    public void testDirectWorkerAccess(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);

        // Try accessing worker directly with trailing slash
        String worker1Url = cluster.getWorker1().getContainer().getHost() + ":" +
                           cluster.getWorker1().getContainer().getMappedPort(8080);
        String directUrl = "http://" + worker1Url + "/demo/";

        log.info("Trying direct access to worker: {}", directUrl);
        HttpResponse directResponse = httpClient.get(directUrl);
        log.info("Direct worker response: {} - {}", directResponse.getStatusCode(), directResponse.getBody().substring(0, Math.min(200, directResponse.getBody().length())));

        // Try accessing via balancer with trailing slash
        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/demo/";
        log.info("Trying balancer access: {}", balancerUrl);
        HttpResponse balancerResponse = httpClient.get(balancerUrl);
        log.info("Balancer response: {} - {}", balancerResponse.getStatusCode(), balancerResponse.getBody());

        // Check mod_cluster proxy info
        org.wildfly.extras.creaper.core.online.operations.Operations ops = cluster.getWorker1().getOperations();
        org.wildfly.extras.creaper.core.online.operations.Address mcAddress =
            org.wildfly.extras.creaper.core.online.operations.Address.subsystem("modcluster");

        org.wildfly.extras.creaper.core.online.ModelNodeResult proxyListResult =
            ops.readAttribute(mcAddress.and("proxy", "default"), "proxies");
        proxyListResult.assertSuccess();
        log.info("Proxy list on worker: {}", proxyListResult.value());

        // Check full mod_cluster proxy configuration
        org.wildfly.extras.creaper.core.online.ModelNodeResult proxyConfigResult =
            ops.readResource(mcAddress.and("proxy", "default"),
                org.wildfly.extras.creaper.core.online.operations.ReadResourceOption.INCLUDE_RUNTIME);
        log.info("Worker proxy config: status={}, enabled={}, listener={}",
            proxyConfigResult.value().get("status"),
            proxyConfigResult.value().get("enabled"),
            proxyConfigResult.value().get("listener"));

        // Check the outbound-socket-binding
        org.wildfly.extras.creaper.core.online.operations.Address socketBindingAddr =
            org.wildfly.extras.creaper.core.online.operations.Address
                .of("socket-binding-group", "standard-sockets")
                .and("remote-destination-outbound-socket-binding", "modcluster-balancer");
        org.wildfly.extras.creaper.core.online.ModelNodeResult socketBindingResult =
            ops.readResource(socketBindingAddr);
        log.info("Worker outbound-socket-binding: {}", socketBindingResult.value());

        // Check network setup - compare network IDs properly
        String balancerNetworkId = cluster.getBalancer().getNetwork().getId();
        String workerNetworkName = cluster.getWorker1().getContainer().getContainerInfo()
            .getNetworkSettings().getNetworks().keySet().iterator().next();
        String workerNetworkId = cluster.getWorker1().getContainer().getContainerInfo()
            .getNetworkSettings().getNetworks().get(workerNetworkName).getNetworkID();
        log.info("Balancer network ID: {}", balancerNetworkId);
        log.info("Worker network name: {}, ID: {}", workerNetworkName, workerNetworkId);
        log.info("Same network? {}", balancerNetworkId.equals(workerNetworkId));

        // Check balancer's Undertow subsystem configuration
        org.wildfly.extras.creaper.core.online.OnlineManagementClient balancerClient =
            org.wildfly.extras.creaper.core.ManagementClient.online(
                org.wildfly.extras.creaper.core.online.OnlineOptions.standalone()
                    .hostAndPort(cluster.getBalancer().getContainer().getHost(),
                                cluster.getBalancer().getContainer().getMappedPort(9990))
                    .auth("admin", "admin")
                    .build()
            );

        org.wildfly.extras.creaper.core.online.operations.Operations balancerOps =
            new org.wildfly.extras.creaper.core.online.operations.Operations(balancerClient);

        org.wildfly.extras.creaper.core.online.operations.Address filterAddr =
            org.wildfly.extras.creaper.core.online.operations.Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

        org.wildfly.extras.creaper.core.online.ModelNodeResult filterConfig = balancerOps.readResource(filterAddr);
        log.info("Balancer filter config: {}", filterConfig.value());

        // Check if any balancers/nodes are registered (following noe-tests pattern)
        org.wildfly.extras.creaper.core.online.operations.Address modclusterFilterAddr2 =
            org.wildfly.extras.creaper.core.online.operations.Address.subsystem("undertow")
                .and("configuration", "filter")
                .and("mod-cluster", "modcluster");

        org.wildfly.extras.creaper.core.online.ModelNodeResult balancersResult =
            balancerOps.readChildrenNames(modclusterFilterAddr2, "balancer");
        log.info("Registered balancers: {}", balancersResult.value());

        // If there are balancers, check for nodes inside them
        if (balancersResult.value().asList().size() > 0) {
            String balancerName = balancersResult.value().asList().get(0).asString();
            log.info("Checking balancer: {}", balancerName);
            org.wildfly.extras.creaper.core.online.operations.Address balancerAddr =
                modclusterFilterAddr2.and("balancer", balancerName);
            org.wildfly.extras.creaper.core.online.ModelNodeResult nodesResult =
                balancerOps.readChildrenNames(balancerAddr, "node");
            log.info("Registered nodes in balancer '{}': {}", balancerName, nodesResult.value());
        }

        balancerClient.close();

        // Check balancer logs - show last 50 lines to see what's happening
        log.info("===== BALANCER LOGS (last 50 lines) =====");
        String balancerLogs = cluster.getBalancer().getContainer().getLogs();
        String[] logLines = balancerLogs.split("\n");
        int start = Math.max(0, logLines.length - 50);
        for (int i = start; i < logLines.length; i++) {
            log.info("BALANCER: {}", logLines[i]);
        }
        log.info("=========================");

        // Wait a bit and try again
        Thread.sleep(10000);
        log.info("Trying balancer access again after 10s wait: {}", balancerUrl);
        HttpResponse balancerResponse2 = httpClient.get(balancerUrl);
        log.info("Balancer response 2: {} - Body length: {}", balancerResponse2.getStatusCode(), balancerResponse2.getBody().length());

        if (balancerResponse2.getStatusCode() == 200) {
            log.info("SUCCESS! Balancer returned 200");
        } else {
            log.warn("Still 404 after 10s wait");
        }
    }
}
