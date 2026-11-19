package lol.candyriya.continuum.messaging.events;

import lol.candyriya.continuum.common.protocol.EventPayload;

/**
 * Node reported a heartbeat-ish health signal for a backend. Emitted
 * periodically and on status transitions; consumers should treat it as a
 * hint, not as ground truth - health decisions live in the session manager.
 */
public record BackendHealthPayload(String backendId,
                                   HealthStatus status,
                                   int playersOnline,
                                   int maxPlayers) implements EventPayload {
}
