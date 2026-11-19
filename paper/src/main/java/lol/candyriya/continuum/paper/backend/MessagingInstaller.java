package lol.candyriya.continuum.paper.backend;

import lol.candyriya.continuum.common.config.ContinuumConfig;
import lol.candyriya.continuum.paper.messaging.MessengerRegistrar;

/**
 * Factory for the messaging integration. Called once during composition, after
 * the join gate is armed — handlers may then rely on both (via the supplied
 * registrar) being fully wired.
 *
 * <p>Implementations register their incoming listeners through {@code registrar}
 * and return a handle used to tear everything down on shutdown.</p>
 */
@FunctionalInterface
public interface MessagingInstaller {

    /** Install messaging against the registrar; the returned handle is closed on shutdown. */
    MessagingRegistration install(MessengerRegistrar registrar, ContinuumConfig config);
}
