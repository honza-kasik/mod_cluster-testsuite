package org.jboss.modcluster.test.utils;

/**
 * Shared utility methods for Testcontainers and Docker/Podman operations.
 */
public final class ContainerUtils {

    private ContainerUtils() {
    }

    /**
     * Checks if an exception represents a transient Docker/Podman socket error.
     * Traverses the entire exception cause chain looking for SIGPIPE, broken pipe,
     * connection reset, or socket closed errors (including Czech locale variants).
     *
     * @param throwable exception to check
     * @return true if this is a transient socket error that may succeed on retry
     */
    public static boolean isTransientDockerError(final Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            final String message = current.getMessage();
            if (message != null && (message.contains("SIGPIPE")
                    || message.contains("Broken pipe")
                    || message.contains("Connection reset")
                    || message.contains("Socket closed"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
