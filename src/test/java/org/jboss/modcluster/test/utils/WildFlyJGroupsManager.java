package org.jboss.modcluster.test.utils;

import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;

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
}
