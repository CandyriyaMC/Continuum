package lol.candyriya.continuum.velocity.session;

/**
 * Decides how to reconcile a kick/loss signal from a backend.
 *
 * <p>Implementations run on the event path: they must not block on I/O and
 * must not throw - a policy that blows up the proxy's event dispatch helps
 * nobody. Do the bookkeeping inline, hand the heavy work to a scheduler.</p>
 */
public interface KickDecisionPolicy {

    /**
     * Reconciles one kick/loss signal.
     *
     * @param context what happened
     */
    void reconcile(KickContext context);
}
