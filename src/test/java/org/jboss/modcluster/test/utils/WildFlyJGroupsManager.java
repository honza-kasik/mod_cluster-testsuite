package org.jboss.modcluster.test.utils;

import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;

import org.awaitility.core.ConditionTimeoutException;
import org.testcontainers.containers.Container.ExecResult;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Manages JGroups subsystem configuration for WildFly containers.
 * Handles switching from UDP multicast to TCP unicast discovery,
 * which is required for container-based environments where multicast
 * does not work (Docker/Podman networks).
 */
public class WildFlyJGroupsManager {

    private static final Logger log = LoggerFactory.getLogger(WildFlyJGroupsManager.class);

    private final WildFlyContainer container;

    WildFlyJGroupsManager(final WildFlyContainer container) {
        this.container = container;
    }

    /**
     * Configure JGroups to use TCP transport with TCPPING discovery.
     * Required because UDP multicast discovery does not work in Docker/Podman networks.
     * Workers discover each other using container network aliases (worker1, worker2, etc.).
     * Changes are persistent and take effect after reload.
     */
    public void configureTcpDiscovery() {
        try {
            Operations ops = container.getOperations();

            // Switch JGroups channel from UDP to TCP stack
            Address channelAddress = Address.subsystem("jgroups").and("channel", "ee");
            ops.writeAttribute(channelAddress, "stack", "tcp").assertSuccess();

            // Remove MPING (multicast-based discovery, unusable in containers).
            // In WildFly 31+, MPING is a socket-discovery-protocol, not a regular protocol.
            // Try both resource types — one will match, the other is a no-op.
            ops.removeIfExists(Address.subsystem("jgroups")
                .and("stack", "tcp")
                .and("socket-discovery-protocol", "MPING"));
            ops.removeIfExists(Address.subsystem("jgroups")
                .and("stack", "tcp")
                .and("protocol", "MPING"));

            // Configure TCP transport for container networking.
            // When JGroups binds to 0.0.0.0, it auto-detects a physical address via
            // InetAddress.getLocalHost(). In Podman rootless containers this often resolves
            // to 127.0.0.1 or a wrong interface, making the node unreachable by peers.
            // Setting external_addr forces JGroups to publish the Docker/Podman
            // DNS-resolvable hostname instead, and increasing sock_conn_timeout handles
            // the extra latency in Podman rootless networking (slirp4netns/pasta).
            Address tcpTransport = Address.subsystem("jgroups")
                .and("stack", "tcp")
                .and("transport", "TCP");
            ops.invoke("map-put", tcpTransport,
                Values.of("name", "properties")
                    .and("key", "external_addr")
                    .and("value", container.getName())).assertSuccess();
            ops.invoke("map-put", tcpTransport,
                Values.of("name", "properties")
                    .and("key", "sock_conn_timeout")
                    .and("value", "10000")).assertSuccess();
            log.info("JGroups TCP transport configured: external_addr='{}', sock_conn_timeout=10000 on worker '{}'",
                container.getName(), container.getName());

            // Increase GMS join_timeout from default 2s to 10s.
            // In Podman rootless, TCP connections between containers may take several
            // seconds due to SYN retransmits through slirp4netns/pasta networking.
            Address gmsAddress = Address.subsystem("jgroups")
                .and("stack", "tcp")
                .and("protocol", "pbcast.GMS");
            ops.invoke("map-put", gmsAddress,
                Values.of("name", "properties")
                    .and("key", "join_timeout")
                    .and("value", "10000")).assertSuccess();

            // Add TCPPING at position 0 (top of stack) with container network aliases.
            // add-index=0 is critical: discovery protocols must be at the top of the
            // JGroups protocol stack. Without it, TCPPING is appended at the end and
            // cluster discovery fails, breaking Infinispan and distributable sessions.
            Address tcppingAddress = Address.subsystem("jgroups")
                .and("stack", "tcp")
                .and("protocol", "TCPPING");

            if (!ops.exists(tcppingAddress)) {
                ModelNode properties = new ModelNode();
                properties.get("initial_hosts").set(
                    "worker1[7600],worker2[7600],worker3[7600],worker4[7600]");
                properties.get("port_range").set("0");

                ops.add(tcppingAddress, Values.of("add-index", 0)
                    .and("properties", properties)).assertSuccess();
            }

            log.info("JGroups TCP clustering configured on worker '{}'", container.getName());
        } catch (Exception e) {
            log.warn("Failed to configure JGroups TCP on worker '{}': {}", container.getName(), e.getMessage());
        }
    }

    /**
     * Get the number of members in the JGroups cluster view.
     * Reads the runtime {@code view} attribute of the {@code ee} channel.
     * The view string format is: {@code [coordinator|view-id] (member-count) [member1, member2, ...]}.
     *
     * @return number of cluster members, or 1 if view cannot be read/parsed
     */
    public int getClusterViewSize() {
        try {
            Operations ops = container.getOperations();
            Address channelAddr = Address.subsystem("jgroups").and("channel", "ee");
            ModelNodeResult result = ops.readAttribute(channelAddr, "view");

            if (!result.isSuccess() || !result.hasDefined("result")) {
                log.debug("JGroups view not available on '{}' (not running or not defined)", container.getName());
                return 1;
            }

            String view = result.stringValue();
            // Parse "(N)" from the view string to get member count
            Matcher matcher = Pattern.compile("\\((\\d+)\\)").matcher(view);
            if (matcher.find()) {
                int size = Integer.parseInt(matcher.group(1));
                log.debug("JGroups cluster view on '{}': {} (size={})", container.getName(), view, size);
                return size;
            }

            log.debug("Could not parse JGroups view on '{}': {}", container.getName(), view);
            return 1;
        } catch (Exception e) {
            log.debug("Error reading JGroups view on '{}': {}", container.getName(), e.getMessage());
            return 1;
        }
    }

    /**
     * Wait until the JGroups cluster has at least the expected number of members.
     * Polls the cluster view until the expected membership count is reached or timeout expires.
     * On timeout, logs network diagnostics to help debug connectivity issues.
     *
     * @param expectedMembers minimum number of expected cluster members
     * @param timeout maximum time to wait
     */
    public void waitForClusterFormation(int expectedMembers, Duration timeout) {
        log.info("Waiting for JGroups cluster to form with {} members on '{}'...", expectedMembers, container.getName());
        try {
            await().atMost(timeout)
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    int size = getClusterViewSize();
                    assertThat(size)
                        .as("JGroups cluster on '%s' should have at least %d members (current: %d)",
                            container.getName(), expectedMembers, size)
                        .isGreaterThanOrEqualTo(expectedMembers);
                });
            log.info("JGroups cluster formed with {} members on '{}'", expectedMembers, container.getName());
        } catch (ConditionTimeoutException e) {
            logNetworkDiagnostics();
            throw e;
        }
    }

    /**
     * Log network diagnostics from this container to help debug cluster formation failures.
     * Tests DNS resolution and TCP connectivity to all worker hostnames.
     */
    private void logNetworkDiagnostics() {
        log.warn("=== Network diagnostics from '{}' (cluster formation failed) ===", container.getName());
        try {
            // Show /etc/hosts to check hostname resolution
            ExecResult hostsResult = container.getContainer().execInContainer("cat", "/etc/hosts");
            log.warn("/etc/hosts on '{}':\n{}", container.getName(), hostsResult.getStdout().trim());

            // Test DNS and TCP connectivity to each worker
            String[] workers = {"worker1", "worker2", "worker3", "worker4"};
            for (String worker : workers) {
                if (worker.equals(container.getName())) continue;

                ExecResult dnsResult = container.getContainer().execInContainer("getent", "hosts", worker);
                log.warn("DNS '{}' from '{}': exit={} result='{}'",
                    worker, container.getName(), dnsResult.getExitCode(), dnsResult.getStdout().trim());

                if (dnsResult.getExitCode() == 0) {
                    ExecResult tcpResult = container.getContainer().execInContainer("bash", "-c",
                        "timeout 3 bash -c 'echo > /dev/tcp/" + worker + "/7600' 2>&1 && echo 'TCP_OK' || echo 'TCP_FAIL'");
                    log.warn("TCP {}:7600 from '{}': {}",
                        worker, container.getName(), tcpResult.getStdout().trim());
                }
            }

            // Show network interfaces
            ExecResult ipResult = container.getContainer().execInContainer("ip", "addr", "show");
            log.warn("Network interfaces on '{}':\n{}", container.getName(), ipResult.getStdout().trim());
        } catch (Exception ex) {
            log.warn("Failed to collect diagnostics from '{}': {}", container.getName(), ex.getMessage());
        }
    }
}
