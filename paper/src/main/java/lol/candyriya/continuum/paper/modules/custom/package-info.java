/**
 * The runtime container for third-party data: Continuum registers one first-party module
 * ({@code continuum.custom}) and every registered custom adapter rides inside it.
 *
 * <p>An adapter is a third-party {@code lol.candyriya.continuum.api.module.SyncAdapter}
 * implementing the public module contract. The module keeps adapters isolated from each
 * other: each adapter gets its own payload entry, its own failure record and its own
 * failure policy ({@code ABORT} for required adapters, {@code SKIP} for optional ones).
 * One bad adapter never takes the rest of the snapshot down with it.</p>
 *
 * <p>Payload wire shape (snake_case via the shared mapper, stable contract):</p>
 *
 * <pre>{@code
 * {
 *   "format": 1,
 *   "captured_at": 1731811200000,
 *   "adapters": [
 *     {
 *       "adapter_id": "myplugin.cool_module",
 *       "adapter_version": 3,
 *       "required": true,
 *       "failure_policy": "ABORT",
 *       "payload": "{\"myKey\":\"myValue\"}",
 *       "captured_at": 1731811200000
 *     }
 *   ],
 *   "failures": [
 *     {
 *       "adapter_id": "myplugin.broken_module",
 *       "adapter_version": 1,
 *       "code": "CAPTURE_FAILED",
 *       "reason": "capture threw RuntimeException: boom"
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>The adapter's own payload stays an opaque JSON string: Continuum never interprets
 * it, the owning adapter does. The string is exactly what was serialized at capture time
 * (UTF-8 JSON produced from the adapter's data type), so it is also exactly what
 * {@code SyncAdapter.migrate(int, String)} expects at restore time.</p>
 *
 * <p>Failure model inside this module (the {@code continuum.custom} module itself is a
 * first-party, required module; the per-adapter policy governs the inside of it):</p>
 * <ul>
 *   <li>optional adapter fails → the failure is recorded in the payload's
 *       failure list and the snapshot degrades to partial; the rest still applies;</li>
 *   <li>required adapter fails → the operation fails: capture completes exceptionally
 *       (no snapshot is persisted), restore completes exceptionally (the player goes
 *       into the recovery flow instead of a half-loaded state).</li>
 * </ul>
 *
 * <p>Honest timeout model: capture/restore of an adapter is invoked on the player's
 * platform thread (never on infrastructure executors). The timeout is a completion
 * deadline — Continuum cannot interrupt a stuck platform call. When the deadline passes,
 * the adapter's stage is detached, a {@code TIMEOUT} failure is recorded and (for
 * writes) the adapter receives a guarded player that refuses further calls. An adapter
 * that never returns can still keep a thread; see {@link
 * lol.candyriya.continuum.paper.modules.custom.AdapterCallGuard}.</p>
 *
 * @since 1.0.0
 */
package lol.candyriya.continuum.paper.modules.custom;
