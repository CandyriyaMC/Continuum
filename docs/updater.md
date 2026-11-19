# Continuum in-plugin updater

This document describes the built-in update mechanism: what it checks, what it
downloads, how it verifies, what it never does, and how to provision the
production signing key. It is the reference for operators and for the release
pipeline.

If you take one sentence from here:

**The updater can fail, be wrong, or be disabled — your server still starts and your players still play.**

## Table of contents

- [Guarantees and non-goals](#guarantees-and-non-goals)
- [Protocol contract](#protocol-contract)
- [Check lifecycle and state machine](#check-lifecycle-and-state-machine)
- [How an update lands (Paper vs Velocity)](#how-an-update-lands-paper-vs-velocity)
- [Configuration: `update.yml`](#configuration-updateyml)
- [Where the license token lives (and why)](#where-the-license-token-lives-and-why)
- [Manifest verification and key provisioning](#manifest-verification-and-key-provisioning)
- [Security properties](#security-properties)
- [Operator runbook](#operator-runbook)
- [First-release checklist (signing)](#first-release-checklist-signing)
- [Design decisions and deviations](#design-decisions-and-deviations)

## Guarantees and non-goals

Guarantees:

- **Fail-open, always.** Every network error, timeout, 5xx, malformed response,
  verification failure or missing key ends in "check later". Nothing here
  blocks server startup, the login flow, the game loop or shutdown.
- **No player data leaves the machine.** No player UUIDs, messages, world
  data, or metrics are part of any request. The only query parameters are
  `build`, `mc`, `java`, `folia`, `channel`.
- **TLS is not weakened.** Default JVM trust store, hostname verification
  on, no certificate pinning (Let's Encrypt rotation makes pinning a
  self-inflicted outage). The Ed25519 manifest signature is the trust anchor
  on top of TLS, not in place of it.
- **The update endpoint never returns more than it must.** One JSON object
  or one artifact; no binaries are ever executed.

Non-goals:

- No auto-applying without a restart. Paper applies at boot; Velocity swaps
  files at enable and the next boot runs the new build. There is no live
  swap of a running JVM, ever.
- No update nagging at players. All output goes to the console/log, one line
  per state change. The only interactive surface is `/continuum update` on
  the Velocity proxy, guarded by the `continuum.update` permission.
- No downgrades on purpose. The service answers what it answers; the client
  does not argue.

## Protocol contract

The binding contract is the license service's `UPDATER_PROTOCOL.md` (schema
1, additive-only). Summary for the client side:

```
GET https://upd.candyriya.xsxs.lol/v1/update?build=<build-N|devbuild-N>&mc=<26.3>&java=<25>&folia=<0|1>&channel=<release|dev>
Authorization: Bearer ctm_live_<32 hex>   # release channel only
```

- `200` → `{ build, sha256, url, manifestUrl?, manifestSignatureUrl?, upToDate?, channel }`
  - `upToDate: true` means nothing to do.
  - Dev channel omits `manifestUrl`, `manifestSignatureUrl`, `upToDate`.
  - Without `manifestUrl` on release, the client refuses the cycle (fail-closed for the artifact).
  - `url` is a presigned R2 GET URL, valid ~5 minutes.
  - Responses also carry `x-request-id`; include it in support tickets.
- `403` → `{ error: { code, message }, redirect }` — the client logs **one
  line per UTC day** ("no valid license — source builds are on GitHub…") and
  pauses release-channel checks. The plugin runs normally.
- `409` → compatibility gate rejected this server. The `message` is surfaced;
  auto-install never happens, and explicit installs are refused too.
- `400` → the query was invalid (e.g. a release jar on the dev channel). Client logs a line and skips.
- `404` → dev build not published. Silent debug log.
- `429` → rate limited or temporarily banned after repeated auth failures. Next cycle is 6h later anyway.
- `502`/`503` → transient. Check later.
- Anything else, any parse failure, any I/O error → check later. Always.

`GET /v1/manifest.json` and `GET /v1/manifest.json.sig` return the signed
manifest (schema 1: `schema, channel, build, sha256, file, mc{min,max},
java{min}, folia, releasedAt`) and its detached Ed25519 signature
(base64, 64 bytes) over the **exact manifest bytes**. The client verifies
before parsing a single field. Unknown top-level manifest keys are tolerated;
`schema != 1` is refused.

Timeouts: 8s connect, 8s per request. At most one retry per check cycle, and
only for network failures — never for 4xx, never for redirect refusals.
Redirects are followed manually, at most three hops, only to `https` URLs
whose host is the service host or ends with an allow-listed suffix
(`.r2.dev` by default; see `update.yml`). `Authorization` is never forwarded
across a host boundary.

## Check lifecycle and state machine

Checks run at enable + 10s, then every 6h, on the updater's own daemon
thread — never on the game thread, never blocking enable. Single-flight: a
second trigger while one is running gets an "already in progress" answer, it
does not pile on.

```
                 ┌──────────────────────────────────────────────┐
                 │                                              │
   IDLE ────────▶ CHECKING ────────┬────────▶ UP_TO_DATE        │
     ▲            │                │                            │
     │            │                ├────────▶ UPDATE_AVAILABLE ──┤
     │            │                │            │           │    │
     │            │                │            │ consent   │    │
     │            ▼                │            ▼           ▼    │
     │         ERROR            ───┴──────   notify      auto-install
     │    (fail-open,      (network /      (log once,    (or explicit
     │     next 6h cycle)   parse / auth)   once per       install cmd)
     │                                      build)              │
     │                                                          ▼
     └─────────────────────────────────────────────────  DOWNLOADING
                                                               │ verify sha256,
                                                               │ backup current jar,
                                                               │ move into plugins/update/
                                                               ▼
                                                             STAGED
                                                               │ restart
                                                               ▼
                                                    next enable: version == staged?
                                                               │
                                                               ▼
                                                     clean boot → backup deleted
```

The consent default is `notify`. Nothing reaches `plugins/update/` without
either `updates.consent: auto-install` in `update.yml` or an explicit
`/continuum update install`.

Console dedup: the "update available" warning is printed once per build
(`pending_notified_version` in `.update-state`); it repeats after a restart
of the notion, not per cycle. The no-license nag is once per UTC day.

## How an update lands (Paper vs Velocity)

### Paper / Folia backends

Paper applies anything in `plugins/update/` at boot, matching on the plugin
descriptor name, not the filename, and it truncates the destination when it
copies — which is why the artifact is verified by Continuum **before** it is
dropped there, and why the current jar is copied to
`<serverRoot>/.continuum-backup/` before anything is written. If the staged
jar turns out to be bad (it won't be — sha256 and signature are checked),
the backup is your recovery path.

### Velocity proxies

Velocity has no update folder (and never shipped one), so the proxy performs
its own rename swap at enable, before anything else runs — before config is
parsed, before the command tree registers:

1. `plugins/update/continuum-velocity.jar` exists? Verify it against the
   sha256 recorded in `.update-state` (or, if staged by hand, at least a
   valid zip structure).
2. The running jar is renamed into `<serverRoot>/.continuum-backup/`.
3. The staged jar is moved into its place atomically.

Velocity loads plugin classes before firing `ProxyInitializeEvent`, so the
running JVM keeps executing the old build for the rest of that boot; the new
build takes over at the **next restart**. The following enable notices the
version match, deletes the backup and closes the loop. On any failure, both
files keep their pre-swap positions and the proxy runs its current build.

`.continuum-backup/` lives **outside** `plugins/` on purpose: a jar inside
`plugins/` would be loaded next to the real one and produce duplicate
plugins.

### Rolling back

1. Stop the server.
2. Move the backup from `<serverRoot>/.continuum-backup/<name>.<epoch>.old`
   back over the plugin jar in `plugins/`.
3. Remove any `plugins/update/continuum-*.jar` or `continuum-*.jar.part`
   that may be present.
4. Start the server. Delete `.update-state` in the data directory if the
   update was half-applied (or leave it — a mismatch is handled gracefully).

## Configuration: `update.yml`

The updater owns exactly one file, `<data>/update.yml`, seeded with
commented defaults on first run:

```yaml
updates:
  # release | dev
  channel: release
  # master switch (enable + every 6h)
  check: true
  # notify (default) | auto-install
  consent: notify
  # ctm_live_... token for the release channel; blank on dev
  license-token: ""
  # redirect targets allowed when chasing the artifact
  redirect-allow-suffixes:
    - .r2.dev
```

Notes:

- Absent keys get defaults. A **broken file disables only the updater** for
  that boot and logs every problem found; nothing else on the node is
  affected. An absent file is not an error — defaults are written and used.
- Dev-channel checks are anonymous; `license-token` is ignored there.
- Setting `channel: dev` does not turn a release jar into a dev build — the
  build passed to the service is `devbuild-<N>` for dev and `build-<N>` for
  release; an unstamped version (e.g. a local `1.0.0` working tree) skips the
  check entirely. Release jars cannot claim devbuilds they don't have, and
  dev jars cannot claim release builds, on purpose.
- `redirect-allow-suffixes` applies to redirects only; the direct service
  host and `https` are still required. Use it only for a CDN you control.

## Where the license token lives (and why)

`UPDATER_PROTOCOL.md` places the token in `config.yml` under `license.token`.
Continuum's `config.yml` is a deliberately closed schema owned by the shared
configuration, and this subsystem is not allowed to widen it. Resolution
order, implemented in `UpdateSettingsLoader`:

1. `updates.license-token` in **`update.yml`** wins.
2. When that is blank, the loader does a raw, lenient, read-only probe of
   `license.token` in `config.yml`. A config file the shared loader would
   reject is never fatal to the updater — the probe tolerates anything.
3. Still blank on `release` → checks are skipped, one line per boot explains
   why, and the plugin runs normally. Dev channel needs no token.

If the licensing step later adds `license.token` to the typed schema, the
probe keeps working unchanged; the two stores never conflict because the
update token in `update.yml` takes precedence and the probe is fallback-only.

## Manifest verification and key provisioning

The trust chain for a release build:

1. `GET /v1/update` over TLS → response contains the download URL and sha256.
2. `GET /v1/manifest.json` → bytes `M`; optional `content-sha256` header is asserted when present.
3. `GET /v1/manifest.json.sig` → base64 → 64-byte Ed25519 signature.
4. Ed25519 verify(sig, `M`, embeddedPublicKey) — **before** `M` is parsed.
5. Channel cross-check: `manifest.channel` must equal the configured channel.
6. Build cross-check: `manifest.build` must equal the service response.
7. Download URL fetched; the artifact's sha256 must equal `manifest.sha256`
   (and, if present, the response's `sha256` — which must equal the manifest's).

Step 4 fails closed: if no public key is embedded in the plugin, verification
reports `NO_PUBLIC_KEY`, the updater logs one line and **disables itself for
the boot** — the server works, updates just don't happen until the key
exists. This is deliberate: a plugin shipping without its trust anchor must
not silently accept unverified bytes.

### Provisioning the production key (one-time, before the first release)

The repository intentionally ships **no** embedded public key. Before tagging
`build-1` (or at whatever point the release pipeline first signs), do this:

```bash
# 1) generate the keypair (openssl 3.x; the private key NEVER enters the repo)
openssl genpkey -algorithm Ed25519 -out manifest-private-key.pem
chmod 600 manifest-private-key.pem

# 2) export the public key as X.509 SubjectPublicKeyInfo DER
openssl pkey -pubout -in manifest-private-key.pem -outform DER -out common/src/main/resources/continuum-manifest-public.key

# 3) verify the roundtrip exactly as the plugin will
openssl dgst -sha256 -sign manifest-private-key.pem -out /tmp/manifest.sig SOME-MANIFEST.json
#    (the release pipeline signs exact manifest bytes with Ed25519, see .github/workflows/release.yml)
base64 -w0 /tmp/manifest.sig   # must decode to 64 bytes

# 4) store the private key where ONLY the release pipeline can read it
#    GitHub → repo Settings → Secrets and variables → Actions → R2_MANIFEST_ED25519_KEY
#    (single line; base64 of the PEM or the PEM itself, matching what the workflow reads)

# 5) commit the PUBLIC key
git add common/src/main/resources/continuum-manifest-public.key
git commit -m "chore(release): embed the manifest signing public key"
```

That is the whole ceremony. Properties of the key format:

- DER-encoded `SubjectPublicKeyInfo` — `KeyFactory.getInstance("Ed25519")` +
  `X509EncodedKeySpec`, exactly what `ClasspathPublicKeySource` and
  `ManifestVerifier` use. `keytool` can also produce/inspect the pair but
  `-outform DER` is not exposed there in a stable cross-platform way.
- Size cap on the embedded key is 4096 bytes; an Ed25519 key is 44 bytes.
- Tests and CI sign with **throwaway** keypairs generated on the fly; no
  production or development private key exists anywhere in this repository.
- After the first real signed release exists, verify end-to-end: fetch
  `https://upd.candyriya.xsxs.lol/v1/manifest.json` and `.sig`, check the
  `content-sha256` header, and confirm a test instance reports staged/applied.

When the key is missing the updater disables itself (see above); that state
is correct and expected until step 2 happens. `docs/release.md` should link
here at first release.

## Security properties

- **Ed25519 via JEP 339** (`Signature.getInstance("Ed25519")`), JDK 15+ only;
  no external crypto dependencies, no Bouncy Castle.
- **Verify-then-parse.** The manifest's Ed25519 check runs on the raw bytes;
  parsing happens only after a valid signature, so no field is ever read from
  an unauthenticated document.
- **Channel is inside the signed payload.** A release-signed manifest can
  never be accepted as `dev` or vice versa; the client also refuses a
  configured/wire channel mismatch before downloading.
- **Redirect allowlist.** `https` only, host must equal the service host or
  match an allow-listed suffix (`.r2.dev` default). No credentials in URLs,
  no non-default ports, no `user@host`, no fragments, at most 3 hops.
- **Size guards.** Manifest 128 KiB, signature 1 KiB, release response
  64 KiB, artifact 128 MiB — the stream dies before the disk fills.
- **The artifact never runs.** It lands in `plugins/update/` (or, on
  Velocity, is moved into `plugins/`) and is only ever loaded by the platform
  at restart.
- **Single-flight.** One check at a time; a concurrent trigger returns
  "already in progress".
- **No host pinning, ever.** Default cacerts; the manifest signature is the
  anchor, not the certificate chain.
- **Fail-open with receipts.** Every refusal lands in the log with a
  one-line reason; state is in `<data>/.update-state` (JSON, atomic writes).

## Operator runbook

- `/continuum update check` — enqueue a check; the answer comes back in chat/console a moment later.
- `/continuum update install` — check, download, verify, stage. For the proxy only; backends work autonomously.
- Permission: `continuum.update`.
- The staged artifact applies at the **next restart**; on Paper that is automatic (boot-time apply), on Velocity the enable-time rename swap applies it.
- Logs to look for: `update checks armed`, `continuum is up to date`, `update available`, `staged`, `update swap`, `clean boot`.
- If the proxy crashes right after applying, boot it, then restore the backup from `<serverRoot>/.continuum-backup/` (see Rolling back) and restart.
- `update.yml` can be deleted at any time; a fresh default is written on next start.
- `.update-state` corrupts? The updater logs a line and re-derives it. Safe to delete manually.

## First-release checklist (signing)

Before cutting the first signed `build-1`:

1. Keypair generated offline; private half stored as the
   `R2_MANIFEST_ED25519_KEY` GitHub secret (release pipeline).
2. Public half committed at `common/src/main/resources/continuum-manifest-public.key`
   (see [Manifest verification and key provisioning](#manifest-verification-and-key-provisioning)).
3. A CI step (or a local dry run) verifies a signed manifest exactly as the
   plugin does — the release pipeline's publish step already round-trips
   fetch → verify per the release design.
4. Smoke-test the flow: tag, publish, run a proxy on the release build,
   `/continuum update check` → staged → restart → new build active.
5. Only then tag `build-1` for real.

## Design decisions and deviations

Recorded here so they are not archaeology later:

1. **Token in `update.yml`, probe of `license.token` as fallback.** The
   shared config schema is closed and owned elsewhere; the protocol's
   `license.token` path is honored through a lenient raw read instead. (See
   [Where the license token lives](#where-the-license-token-lives-and-why).)
2. **No embedded key in this commit.** Shipping a throwaway public key was
   rejected; the plugin disables the updater with one warning until the real
   production key is committed as described above.
3. **Release manifest contract follows `UPDATER_PROTOCOL.md` exactly** —
   `{ schema, channel, build, sha256, file, mc{min,max}, java{min}, folia,
   releasedAt }` — not the earlier draft manifest shape (`artifacts`,
   `commit`, `compat`, `builtAt`). The live protocol document is binding;
   unknown keys would be tolerated anyway.
4. **Timeouts are 8s connect / 8s read** (project research is binding here);
   the protocol's 10s budget is met comfortably.
5. **`/continuum update` output is not routed through the locale catalogs.**
   The i18n overlay files outside the updater's scope were not touched; the
   lines are complete, hand-written English sentences. Wiring them into the
   catalogs (`velocity.update.*`) is a follow-up once the key provisioning
   lands and the license UX is final.
6. **Paper never performs a swap.** It stages into `plugins/update/` and lets
   Paper apply at boot (Paper matches by plugin name, not filename).
7. **Backups live outside `plugins/`** at `<serverRoot>/.continuum-backup/`,
   with a 24h expiry for orphaned `.old` files and an exact-path guard before
   any deletion.
8. **`.update-state` lives in the plugin's data directory** (`<data>/.update-state`),
   JSON, atomic writes, corruption-tolerant (renames aside and continues).
9. **`schema != 1` is refused, not parsed.** Forward compatibility is by
   additive keys only; a breaking change ships as schema 2 with new client
   semantics.
10. **Compat is double-gated.** The service can 409; the client independently
    checks `java.min`, `mc.min/max`, and the `folia` claim before staging —
    a Folia server never stages a build that doesn't claim Folia support.
