package lol.candyriya.continuum.storage.sql;

import lol.candyriya.continuum.common.error.StorageException;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * JSON array codec for the {@code modules_json} column: {@code ["inventory","vitals"]}.
 * Plain strings, no module metadata — history listings need identities, not payloads.
 */
final class ModulesJson {

    private static final JsonMapper MAPPER = new JsonMapper();

    private ModulesJson() {
    }

    static String encode(List<String> modules) {
        if (modules == null || modules.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(modules);
        } catch (Exception e) {
            // List<String> cannot realistically fail to encode; wrap loudly if it does
            throw new IllegalStateException("failed to encode module list", e);
        }
    }

    static List<String> decode(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(java.util.Arrays.asList(MAPPER.readValue(json, String[].class)));
        } catch (Exception e) {
            throw StorageException.unavailable("stored modules_json is not a JSON string array", e);
        }
    }
}
