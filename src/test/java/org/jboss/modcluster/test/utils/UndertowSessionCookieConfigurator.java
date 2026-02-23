package org.jboss.modcluster.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;

/**
 * Utility for configuring session cookie settings via Undertow subsystem.
 * Supports custom cookie names, paths, and HTTP-only flags.
 * Uses Creaper Operations API for management operations.
 */
public class UndertowSessionCookieConfigurator {

    private static final Logger log = LoggerFactory.getLogger(UndertowSessionCookieConfigurator.class);

    /**
     * Sets a custom session cookie name on the specified worker.
     * If cookieName is null, uses default (JSESSIONID).
     * Requires server reload to take effect.
     *
     * @param worker Container to configure
     * @param cookieName Custom cookie name, or null for default
     * @throws Exception if configuration fails
     */
    public void setSessionCookieName(final WildFlyContainer worker, final String cookieName) throws Exception {
        log.info("Configuring session cookie name '{}' on worker '{}'", cookieName, worker.getName());

        final Operations ops = worker.getOperations();
        final Address sessionCookieAddr = Address.subsystem("undertow")
            .and("servlet-container", "default")
            .and("setting", "session-cookie");

        // Add session-cookie setting if it doesn't exist
        if (!ops.exists(sessionCookieAddr)) {
            log.debug("Creating session-cookie configuration");
            ops.add(sessionCookieAddr, Values.of("http-only", true)).assertSuccess();
        }

        // Set custom name if provided
        if (cookieName != null) {
            log.debug("Setting cookie name to '{}'", cookieName);
            ops.writeAttribute(sessionCookieAddr, "name", cookieName).assertSuccess();
        }

        // Reload to apply changes
        log.debug("Reloading server to apply session cookie configuration");
        worker.reload();

        log.info("Session cookie name '{}' configured on worker '{}'", cookieName, worker.getName());
    }
}
