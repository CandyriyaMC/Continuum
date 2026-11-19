/**
 * Player metadata sync: the two plugin-owned profile names — the display name
 * ({@code Player#displayName()}) and the tab-list name
 * ({@code Player#playerListName()}) — kept consistent across backends.
 *
 * <p>Everything else that smells like "metadata" is deliberately excluded:
 * the Bukkit {@code Metadatable} store (deprecated), runtime entity flags
 * (glow, invisibility, freeze ticks, ...), send-only clientbound state
 * (tab-list header/footer), read-only client hints (locale, brand), anything
 * derived from the scoreboard family, and any family another module owns
 * (PDC, flight abilities, vitals, ...). Each exclusion is documented with its
 * reason and machine-checked in {@link lol.candyriya.continuum.paper.modules.metadata.MetadataEntry};
 * the same matrix is restated in prose in
 * {@link lol.candyriya.continuum.paper.modules.metadata.MetadataModule}.</p>
 *
 * <p>Restore is an unconditional exact mirror: captured values are re-applied
 * as stored, absent values reset the target to the vanilla default. Components
 * round-trip as adventure gson component JSON (never MiniMessage), under a
 * 16 KiB per-key budget.</p>
 *
 * <p>Platform access goes through
 * {@link lol.candyriya.continuum.paper.modules.metadata.ProfileOps} so the
 * capture/restore logic stays unit-testable without a server.</p>
 */
package lol.candyriya.continuum.paper.modules.metadata;
