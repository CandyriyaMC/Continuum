package lol.candyriya.continuum.velocity.session;

/**
 * The token source the production bootstrap wires until storage lands.
 *
 * <p>Every call fails with a clear message naming the missing piece, instead
 * of silently handing out tokens the database never agreed to. Anything that
 * demands a token today should fail loudly, not limp along unfenced.</p>
 */
public final class UnavailableSessionTokenSource implements SessionTokenSource {

    @Override
    public long nextToken() throws SessionTokenException {
        throw new SessionTokenException(
                "storage-backed session tokens are not wired yet; the transfer orchestrator seeds monotonic BIGINT tokens from the database");
    }
}
