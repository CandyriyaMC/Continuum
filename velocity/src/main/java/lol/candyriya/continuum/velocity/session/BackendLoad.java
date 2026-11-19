package lol.candyriya.continuum.velocity.session;

import java.util.Objects;

/**
 * How many players the registry currently counts on a backend.
 *
 * @param backendName name as configured in velocity.toml
 * @param connections players currently connected to it (kicked players awaiting a backend
 *                    outcome are not counted)
 */
public record BackendLoad(String backendName, int connections) {

    public BackendLoad {
        Objects.requireNonNull(backendName, "backendName");
        if (connections < 0) {
            throw new IllegalArgumentException("connections must not be negative, got " + connections);
        }
    }
}
