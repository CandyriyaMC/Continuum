package lol.candyriya.continuum.common.protocol;

/**
 * Marker for typed event payloads carried inside {@link MessageEnvelope}.
 *
 * <p>Deliberately open: every module defines its own payload records in its
 * own package. Payloads must be Jackson-mappable records — no cycles, no
 * custom getters, no {@code @JsonIgnore} tricks — because a payload that
 * serializes differently on two nodes is a split-brain waiting to happen.</p>
 */
public interface EventPayload {
}
