package lol.candyriya.continuum.velocity.handoff;

/**
 * Outcome of {@link HandoffCoordinator#initiate(com.velocitypowered.api.proxy.Player, String)}.
 *
 * @param initiated true when the switch was accepted and the connect is being driven
 * @param reason    English explanation; empty when initiated
 * @param targetBackend the backend the player was pointed at, or {@code null} when refused
 */
public record InitiateResult(boolean initiated, String reason, String targetBackend) {

    public static InitiateResult initiated(String targetBackend) {
        return new InitiateResult(true, "", targetBackend);
    }

    public static InitiateResult refused(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("refusal needs an explanation");
        }
        return new InitiateResult(false, reason, null);
    }
}
