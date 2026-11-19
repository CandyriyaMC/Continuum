package lol.candyriya.continuum.common.snapshot;

import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * One module's slice of a snapshot.
 *
 * @param moduleId      stable module id (see KnownModules); part of the storage contract
 * @param moduleVersion producer-defined payload version, bumped by the module itself
 * @param payload       module-defined JSON object; kept as a tree so the envelope layer
 *                      never needs to know the module's DTO classes
 */
public record ModuleSnapshot(String moduleId, int moduleVersion, JsonNode payload) {

    public ModuleSnapshot {
        Objects.requireNonNull(moduleId, "moduleId must not be null");
        if (moduleId.isBlank()) {
            throw new IllegalArgumentException("moduleId must not be blank");
        }
        if (moduleVersion < 1) {
            throw new IllegalArgumentException("moduleVersion must be >= 1, got " + moduleVersion);
        }
        Objects.requireNonNull(payload, "payload must not be null");
        if (!payload.isObject()) {
            throw new IllegalArgumentException("payload must be a JSON object, got " + payload.getNodeType());
        }
    }
}
