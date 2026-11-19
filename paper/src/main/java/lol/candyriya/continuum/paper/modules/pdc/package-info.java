/**
 * Player {@code PersistentDataContainer} synchronization.
 *
 * <p>Capture reads every key the live player container holds, keeps the ones whose
 * storage type can be carried losslessly, skips hard-denied namespaces (credentials,
 * second-factor state) and anything over the size budget, and encodes the rest as a
 * versioned JSON payload ({@link lol.candyriya.continuum.paper.modules.pdc.PdcPayload}).</p>
 *
 * <p>Restore is strictly additive: it writes the allowed entries and never removes a
 * key. Foreign data on the target is never touched. See
 * {@link lol.candyriya.continuum.paper.modules.pdc.PdcModule} for the full contract,
 * including the deny-list inversion and the stale-key tradeoff.</p>
 */
package lol.candyriya.continuum.paper.modules.pdc;
