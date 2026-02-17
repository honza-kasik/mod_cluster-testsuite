package org.jboss.modcluster.test.utils;

import org.jboss.modcluster.test.base.BalancerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Container wrapper for load balancers (Undertow or httpd with mod_cluster).
 */
public abstract class BalancerContainer {

    private static final Logger log = LoggerFactory.getLogger(BalancerContainer.class);

    protected GenericContainer<?> container;
    protected Network network;
    protected BalancerType type;

    protected static final int HTTP_PORT = 8080;
    protected static final int HTTPS_PORT = 8443;
    protected static final int MCMP_PORT = 6666;
    protected static final int MANAGEMENT_PORT = 9990;

    public static BalancerContainer create(BalancerType type) {
        switch (type) {
            case UNDERTOW:
                return new UndertowBalancerContainer();
            case HTTPD:
                return new HttpdBalancerContainer();
            default:
                throw new IllegalArgumentException("Unknown balancer type: " + type);
        }
    }

    public abstract void start();

    public void stop() {
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }

    public String getHttpUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(HTTP_PORT);
    }

    public String getHttpsUrl() {
        return "https://" + container.getHost() + ":" + container.getMappedPort(HTTPS_PORT);
    }

    public String getMcmpUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(MCMP_PORT);
    }

    public String getInternalHttpUrl() {
        return "http://" + container.getContainerInfo().getConfig().getHostName() + ":" + HTTP_PORT;
    }

    public Network getNetwork() {
        return network;
    }

    public GenericContainer<?> getContainer() {
        return container;
    }

    public BalancerType getType() {
        return type;
    }

    /**
     * Undertow-based mod_cluster balancer.
     * Uses the same WildFly/EAP ZIP as workers, but configured as a load balancer.
     */
    static class UndertowBalancerContainer extends BalancerContainer {

        @Override
        public void start() {
            type = BalancerType.UNDERTOW;
            network = Network.newNetwork();

            java.nio.file.Path zipPath = getWildFlyZipPath();

            if (zipPath != null && zipPath.toFile().exists()) {
                log.info("Building Undertow balancer from ZIP: {}", zipPath);
                startFromZip(zipPath);
            } else {
                log.info("No ZIP provided, using pre-built Undertow balancer image");
                startFromImage();
            }
        }

        private void startFromZip(Path zipPath) {
            String zipFileName = zipPath.getFileName().toString();
            String javaVersion = getRequiredJavaVersion(zipFileName);

            // Generate consistent image tag (same as workers, image is reused!)
            String imageTag = ImageBuilder.generateImageTag(zipFileName, javaVersion);

            // Check if image already exists (might have been built by worker)
            if (!ImageBuilder.imageExists(imageTag)) {
                log.info("Building Undertow balancer from ZIP: {} (this may take a few minutes on first run)", zipFileName);
                ImageBuilder.buildImageFromZip(zipPath, javaVersion, imageTag);
            } else {
                log.info("Using existing image: {}", imageTag);
            }

            // Start with balancer configuration in --admin-only mode (like noe-tests)
            // Use standalone.xml (NOT standalone-ha.xml) - we'll configure mod_cluster filter
            container = new GenericContainer<>(imageTag)
                    .withNetwork(network)
                    .withNetworkAliases("balancer")
                    .withExposedPorts(HTTP_PORT, HTTPS_PORT, MANAGEMENT_PORT)
                    .withCommand("/opt/wildfly/bin/standalone.sh",
                                "-Djboss.node.name=balancer",
                                "-bmanagement", "0.0.0.0",
                                "--admin-only")
                    .waitingFor(Wait.forLogMessage(".*WFLYSRV0025.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(5)))
                    .withLogConsumer(outputFrame ->
                            log.debug("[UNDERTOW-BALANCER] {}", outputFrame.getUtf8String().trim()));

            container.start();
            log.info("Undertow balancer started in admin-only mode on network: {}", network.getId());

            // Configure the balancer to act as a balancer (not a worker) - all changes in admin-only mode
            configureAsBalancer();
        }

        /**
         * Configure this WildFly instance to act as a mod_cluster balancer.
         * Uses Creaper Operations API following the order from noe-tests CLILib.
         * Server must be started in --admin-only mode to avoid reload-required state.
         */
        private void configureAsBalancer() {
            try {
                // Wait for management interface
                Thread.sleep(5000);

                org.wildfly.extras.creaper.core.online.OnlineManagementClient client =
                    org.wildfly.extras.creaper.core.ManagementClient.online(
                        org.wildfly.extras.creaper.core.online.OnlineOptions.standalone()
                            .hostAndPort(container.getHost(), container.getMappedPort(MANAGEMENT_PORT))
                            .auth("admin", "admin")
                            .build()
                    );

                org.wildfly.extras.creaper.core.online.operations.Operations ops =
                    new org.wildfly.extras.creaper.core.online.operations.Operations(client);

                log.info("Configuring Undertow mod_cluster filter on balancer (admin-only mode)");

                // Step 0: Get container's internal IP and configure public interface to use it
                String containerIp = container.getContainerInfo().getNetworkSettings().getNetworks()
                    .values().iterator().next().getIpAddress();
                log.info("Container IP: {}", containerIp);

                org.wildfly.extras.creaper.core.online.operations.Address publicInterfaceAddr =
                    org.wildfly.extras.creaper.core.online.operations.Address.of("interface", "public");

                // Undefine any-address first, then set inet-address
                ops.undefineAttribute(publicInterfaceAddr, "any-address");
                ops.writeAttribute(publicInterfaceAddr, "inet-address", containerIp)
                    .assertSuccess("Failed to configure public interface");
                log.info("Public interface configured to: {}", containerIp);

                // Step 1: Create dedicated socket binding for MCMP (must not be wildcard 0.0.0.0)
                org.wildfly.extras.creaper.core.online.operations.Address mcmpSocketAddr =
                    org.wildfly.extras.creaper.core.online.operations.Address
                        .of("socket-binding-group", "standard-sockets")
                        .and("socket-binding", "modcluster-mcmp");

                ops.add(mcmpSocketAddr,
                    org.wildfly.extras.creaper.core.online.operations.Values.of("port", HTTP_PORT)
                        .and("interface", "public"))
                    .assertSuccess("Failed to add MCMP socket binding");
                log.info("MCMP socket binding created");

                // Step 2: Create multicast socket binding for advertisement
                org.wildfly.extras.creaper.core.online.operations.Address multicastAddr =
                    org.wildfly.extras.creaper.core.online.operations.Address
                        .of("socket-binding-group", "standard-sockets")
                        .and("socket-binding", "modcluster");

                ops.add(multicastAddr,
                    org.wildfly.extras.creaper.core.online.operations.Values.of("port", 0)
                        .and("multicast-address", "224.0.1.105")
                        .and("multicast-port", 23364))
                    .assertSuccess("Failed to add multicast socket binding");
                log.info("Multicast socket binding created");

                // Step 3: Add mod_cluster filter to undertow with dedicated MCMP socket
                org.wildfly.extras.creaper.core.online.operations.Address filterAddr =
                    org.wildfly.extras.creaper.core.online.operations.Address.subsystem("undertow")
                        .and("configuration", "filter")
                        .and("mod-cluster", "modcluster");

                ops.add(filterAddr,
                    org.wildfly.extras.creaper.core.online.operations.Values.of("management-socket-binding", "modcluster-mcmp")
                        .and("advertise-socket-binding", "modcluster"))
                    .assertSuccess("Failed to add mod_cluster filter");
                log.info("Mod_cluster filter created");

                // Step 4: Add filter-ref to default-host (following CLILib order)
                org.wildfly.extras.creaper.core.online.operations.Address filterRefAddr =
                    org.wildfly.extras.creaper.core.online.operations.Address.subsystem("undertow")
                        .and("server", "default-server")
                        .and("host", "default-host")
                        .and("filter-ref", "modcluster");

                ops.add(filterRefAddr)
                    .assertSuccess("Failed to add filter-ref");
                log.info("Filter-ref added to default-host");

                // Step 5: Reload from admin-only mode to normal mode (like noe-tests stop/start)
                log.info("Reloading server to transition from admin-only to normal mode");
                new org.wildfly.extras.creaper.core.online.operations.admin.Administration(client).reload();

                client.close();

                log.info("Undertow balancer configured successfully. MCMP listening on port {} (modcluster-mcmp socket-binding)", HTTP_PORT);

            } catch (Exception e) {
                log.error("Failed to configure balancer", e);
                throw new RuntimeException("Balancer configuration failed", e);
            }
        }

        private void startFromImage() {
            String customImage = System.getProperty("balancer.undertow.image");
            String imageName = customImage != null ? customImage : "quay.io/modcluster/mod_cluster-undertow:latest";

            container = new GenericContainer<>(DockerImageName.parse(imageName))
                    .withNetwork(network)
                    .withNetworkAliases("balancer")
                    .withExposedPorts(HTTP_PORT, HTTPS_PORT, MCMP_PORT)
                    .waitingFor(Wait.forHttp("/").forPort(HTTP_PORT))
                    .withLogConsumer(outputFrame -> log.debug("[UNDERTOW] {}", outputFrame.getUtf8String().trim()));

            container.start();
            log.info("Undertow balancer started from pre-built image on network: {}", network.getId());
        }

        private Path getWildFlyZipPath() {
            String zipPath = System.getProperty("wildfly.zip.path");
            if (zipPath != null) {
                return Paths.get(zipPath);
            }

            zipPath = System.getenv("WILDFLY_ZIP_PATH");
            if (zipPath != null) {
                return Paths.get(zipPath);
            }

            File distDir = new File("distributions");
            if (distDir.exists() && distDir.isDirectory()) {
                File[] zips = distDir.listFiles((dir, name) ->
                    name.startsWith("wildfly-") && name.endsWith(".zip") ||
                    name.startsWith("jboss-eap-") && name.endsWith(".zip"));

                if (zips != null && zips.length > 0) {
                    return zips[0].toPath();
                }
            }

            return null;
        }

        /**
         * Determine required Java version based on WildFly/EAP version.
         * Can be overridden via system property: -Dcontainer.java.version=17 or -Dcontainer.java.version=11
         */
        private String getRequiredJavaVersion(String zipFileName) {
            // Check for explicit override first
            String javaVersionOverride = System.getProperty("container.java.version");
            if (javaVersionOverride != null && !javaVersionOverride.trim().isEmpty()) {
                String javaImage = "openjdk-" + javaVersionOverride;
                log.info("Using Java version from system property: {} ({})", javaVersionOverride, javaImage);
                return javaImage;
            }

            // Auto-detect based on filename
            if (zipFileName.startsWith("wildfly-")) {
                String versionPart = zipFileName.substring(8);
                String majorVersion = versionPart.split("\\.")[0];

                try {
                    int major = Integer.parseInt(majorVersion);
                    return major >= 31 ? "openjdk-17" : "openjdk-11";
                } catch (NumberFormatException e) {
                    log.warn("Could not parse WildFly version, defaulting to Java 17");
                    return "openjdk-17";
                }
            } else if (zipFileName.startsWith("jboss-eap-")) {
                String versionPart = zipFileName.substring(10);
                String majorVersion = versionPart.split("\\.")[0];

                try {
                    int major = Integer.parseInt(majorVersion);
                    return major >= 8 ? "openjdk-17" : "openjdk-11";
                } catch (NumberFormatException e) {
                    log.warn("Could not parse EAP version, defaulting to Java 17");
                    return "openjdk-17";
                }
            }

            log.warn("Unknown distribution format, defaulting to Java 17");
            return "openjdk-17";
        }
    }

    /**
     * Apache httpd with mod_cluster balancer.
     */
    static class HttpdBalancerContainer extends BalancerContainer {

        @Override
        public void start() {
            type = BalancerType.HTTPD;
            network = Network.newNetwork();

            // Try custom image first, fall back to default
            String customImage = System.getProperty("balancer.httpd.image");
            String imageName = customImage != null ? customImage : "quay.io/modcluster/mod_cluster-httpd:latest";

            container = new GenericContainer<>(DockerImageName.parse(imageName))
                    .withNetwork(network)
                    .withNetworkAliases("balancer")
                    .withExposedPorts(HTTP_PORT, HTTPS_PORT, MCMP_PORT)
                    .waitingFor(Wait.forHttp("/").forPort(HTTP_PORT))
                    .withLogConsumer(outputFrame -> log.debug("[HTTPD] {}", outputFrame.getUtf8String().trim()));

            container.start();
            log.info("Httpd balancer started on network: {}", network.getId());
        }
    }
}
