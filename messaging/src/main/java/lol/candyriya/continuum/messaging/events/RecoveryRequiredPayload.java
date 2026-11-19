package lol.candyriya.continuum.messaging.events;

import lol.candyriya.continuum.common.protocol.EventPayload;

import java.util.UUID;

/**
 * Something left a session in a state that needs operator (or automatic)
 * attention: an interrupted transfer, an unresolvable snapshot conflict, a
 * database that went away mid-handoff. Emitted instead of guessing - if the
 * system cannot tell what happened, it says so loudly.
 */
public record RecoveryRequiredPayload(UUID playerId,
                                      UUID sessionId,
                                      String backendId,
                                      String reason) implements EventPayload {
}
