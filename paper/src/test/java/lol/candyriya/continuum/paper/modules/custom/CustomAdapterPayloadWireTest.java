package lol.candyriya.continuum.paper.modules.custom;

import lol.candyriya.continuum.common.internal.JacksonMappers;
import lol.candyriya.continuum.api.module.FailurePolicy;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the wire contract: snake_case keys, enum constants by declared name, epoch-milli
 * instants, unknown properties tolerated on read — the exact conventions the shared
 * snapshot mapper and the platform envelope layer apply.
 */
class CustomAdapterPayloadWireTest {

    private final JsonMapper mapper = JacksonMappers.create();

    private static CustomAdapterPayload samplePayload() {
        var adapter = new AdapterEntry("test.alpha", 2, true, FailurePolicy.ABORT,
                "{\"value\":\"captured\",\"count\":7}", Instant.ofEpochMilli(1731811200000L));
        var failure = new AdapterFailure("test.beta", 1, AdapterFailure.CODE_TIMEOUT,
                "capture timed out after 5000 ms");
        return new CustomAdapterPayload(CustomAdapterPayload.FORMAT,
                Instant.ofEpochMilli(1731811200000L), List.of(adapter), List.of(failure));
    }

    @Test
    void snakeCaseKeysOnTheWire() throws Exception {
        String json = mapper.writeValueAsString(samplePayload());
        assertTrue(json.contains("\"captured_at\""), () -> json);
        assertTrue(json.contains("\"adapter_id\""), () -> json);
        assertTrue(json.contains("\"adapter_version\""), () -> json);
        assertTrue(json.contains("\"failure_policy\""), () -> json);
        assertTrue(json.contains("\"captured_at\":1731811200000"), () -> json);
        assertFalse(json.contains("capturedAt"), () -> json);
        assertFalse(json.contains("adapterId"), () -> json);
    }

    @Test
    void enumConstantsSerializedAsDeclaredNames() throws Exception {
        String json = mapper.writeValueAsString(samplePayload());
        assertTrue(json.contains("\"ABORT\""), () -> json);
    }

    @Test
    void rawAdapterPayloadSurvivesRoundtripVerbatim() throws Exception {
        String json = mapper.writeValueAsString(samplePayload());
        CustomAdapterPayload back = mapper.readValue(json, CustomAdapterPayload.class);
        assertEquals(samplePayload(), back);
        assertEquals("{\"value\":\"captured\",\"count\":7}", back.adapters().get(0).payload());
    }

    @Test
    void unknownPropertiesAreTolerated() throws Exception {
        String json = mapper.writeValueAsString(samplePayload());
        // simulate a newer writer adding a field; the reader must not choke
        String withExtra = json.substring(0, json.length() - 1) + ",\"future_field\":{\"new\":true}}";
        JsonNode tree = mapper.readTree(withExtra);
        CustomAdapterPayload decoded = mapper.treeToValue(tree, CustomAdapterPayload.class);
        assertEquals(samplePayload().adapters().get(0).adapterId(), decoded.adapters().get(0).adapterId());
    }
}
