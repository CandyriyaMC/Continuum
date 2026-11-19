package lol.candyriya.continuum.paper.backend;

import lol.candyriya.continuum.common.config.ContinuumConfig;
import lol.candyriya.continuum.paper.gate.RestoreGate;
import lol.candyriya.continuum.paper.gate.RestoreHandler;

/**
 * Factory for the gate's restore handler. Called once, right after the gate is
 * created, before any listener is armed — the handler may rely on the gate
 * existing and on being called from the entity thread.
 */
@FunctionalInterface
public interface RestoreHandlerFactory {

    /** Create the restore handler. {@code gate} is the fully wired, not yet armed gate. */
    RestoreHandler create(RestoreGate gate);
}
