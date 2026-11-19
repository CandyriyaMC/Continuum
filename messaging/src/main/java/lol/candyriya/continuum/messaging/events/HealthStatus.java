package lol.candyriya.continuum.messaging.events;

/** Coarse backend health, as reported on the wire. */
public enum HealthStatus {
    UP,
    DEGRADED,
    DOWN
}
