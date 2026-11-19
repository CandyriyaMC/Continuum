/**
 * Inventory synchronization modules: storage/armor/offhand, the internal 2×2 crafting grid and
 * the cursor ({@code continuum.inventory}), plus the ender chest ({@code continuum.enderchest}).
 *
 * <p>Two modules, one shared serialization path — {@code ItemStack.serializeItemsAsBytes(...)}
 * per section, base64 for the JSON payload — and a shared, deliberately non-authoritative audit
 * layer for logs and metrics. Restore is atomic: the payload is fully decoded and shape-checked
 * before any live write, and the live state is re-read afterwards and compared against the
 * decode. A silent item loss is not possible on either module's path.</p>
 *
 * <p><b>Threading contract.</b> Capture/restore/validate are entity-affine and must be invoked
 * on the player's owning thread — the main thread on Paper, the player's region thread on
 * Folia. Continuum guarantees this for module lifecycle calls; the modules tripwire-check it.
 * {@code id()}, {@code version()}, {@code priority()} and {@code dependencies()} are constants,
 * safe to call from any thread.</p>
 *
 * <p><b>Open windows are never re-opened</b> after a restore, and foreign container contents are
 * never transferred — see {@link lol.candyriya.continuum.paper.modules.inventory.InventoryModule}
 * for the exact capture-time handling.</p>
 */
package lol.candyriya.continuum.paper.modules.inventory;
