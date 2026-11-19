package lol.candyriya.continuum.paper.backend;

/**
 * Handle for the messaging integration once it registers its plugin-message
 * listeners and sibling transports.
 *
 * <p>The backend composition only ever sees this interface; closing it on
 * shutdown must release every registration it made.</p>
 */
public interface MessagingRegistration extends AutoCloseable {

    /** Tears down everything the messaging integration registered. Idempotent. */
    @Override
    void close();
}
