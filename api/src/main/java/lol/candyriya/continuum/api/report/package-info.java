/**
 * Capture and restore reports — the per-module accounting of every sync operation.
 *
 * <p>Both reports implement {@link lol.candyriya.continuum.api.report.SyncReport}, which
 * exposes the player, the overall verdict and the per-module rows. Reports are the only
 * place third-party code should read &ldquo;did it work?&rdquo; from: the verdict tells
 * you whether the operation achieved its goal, and the module rows tell you exactly what
 * did and did not make it.</p>
 *
 * <p><b>Codes.</b> Module rows carry a stable machine-readable code from
 * {@link lol.candyriya.continuum.api.report.ModuleCodes} plus an optional free-text
 * {@code detail} (English, for logs and diagnostics, never parsed by code).</p>
 *
 * <p><b>Immutability.</b> Reports are deep-frozen snapshots of one operation. They are
 * safe to log, cache, compare and persist; they never change after creation.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
package lol.candyriya.continuum.api.report;

import org.jetbrains.annotations.ApiStatus;
