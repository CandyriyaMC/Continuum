package lol.candyriya.continuum.velocity.session;

/**
 * Source of session tokens.
 *
 * <p>The production contract: the database hands out a strictly monotonically
 * increasing BIGINT per token issue (UPDATE/RETURNING-style atomic counter), and
 * every write that claims session ownership carries the token so the database
 * can reject stale owners inside the same atomic operation.</p>
 *
 * <p>Implementations that cannot honor that (in-memory, clock-based,
 * approximate) must not be wired into the production path.</p>
 */
public interface SessionTokenSource {

    /**
     * Issues the next token.
     *
     * @return a token strictly greater than every token issued so far
     * @throws SessionTokenException when no token can be issued right now
     */
    long nextToken() throws SessionTokenException;
}
