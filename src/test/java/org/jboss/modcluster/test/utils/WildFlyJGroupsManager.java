package org.jboss.modcluster.test.utils;

import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;

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
     *
     * @param expectedMembers minimum number of expected cluster members
     * @param timeout maximum time to wait
     */
    public void waitForClusterFormation(int expectedMembers, Duration timeout) {
        log.info("Waiting for JGroups cluster to form with {} members on '{}'...", expectedMembers, container.getName());
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
    }
}
