# Compatibility policy

Continuum targets the **latest Minecraft release** and treats everything older
as best effort. The reasons are simple: plugins are bound to a specific
platform API surface (`paper-api` / `folia-api`), one `api-version` in the
descriptor ([`paper-plugin.yml`](../paper/src/main/resources/paper-plugin.yml)),
one Java toolchain, and real Mojang servers move forward whether plugins are
ready or not. Always-latest keeps the supported surface honest and small.

This page is the single source of truth for what is supported, what is tested,
and what is not.

## What CI proves per build

The `compat-ci` workflow (see [`.github/workflows/compat-ci.yml`](../.github/workflows/compat-ci.yml))
runs on every pull request against `master` and `dev`, weekly, and on demand.
Each leg:

1. Builds the plugin from source (`:paper:shadowJar`).
2. Downloads the newest **STABLE** server build of the pinned version from
   Fill v3 (`fill.papermc.io`), verifying the SHA-256 checksum and refusing
   non-STABLE channels.
3. Boots a real server with a real MySQL 8 service (the smoke config points
   storage at it). Storage applies its schema migrations when the pool opens,
   and a migration failure refuses to serve.
4. Waits for the `Done (` line in the log (300 s timeout; the server log is
   uploaded as an artifact on failure).
5. Asserts the plugin enabled, the bootstrap ran (`Commands registered at the
   bootstrap stage` in the log), and **no `SEVERE`** appears anywhere.
6. Probes `/continuum status` over RCON and expects a reply that mentions
   Continuum.

The same leg runs for **Paper** and **Folia** (the plugin declares
`folia-supported: true`).

## What CI does *not* prove

- **Cross-server transfer flows.** Booting one backend with MySQL does not
  exercise a transfer between two backends through a Velocity coordinator.
  That needs a live multi-server harness and is not part of this workflow.
- **Fault injection.** Crash/restart, broker loss and half-applied migration
  behavior are covered by the testcontainers-based integration tests in the
  storage and messaging modules, not by the compatibility smoke.
- **Regionized scheduling correctness.** The Folia smoke proves the plugin
  boots and enables on Folia. It does not prove correctness of scheduling
  under concurrent region loads.
- **Performance, gameplay behavior, or data written by older plugin builds.**

## Support matrix

| Minecraft | Platform | Java | Status |
|-----------|----------|------|--------|
| 26.3 | Paper | 25 | CI smoke on every PR (newest STABLE build) |
| 26.3 | Folia | 25 | CI smoke on every PR (newest STABLE build) |
| 1.21.11 | Paper | 21 | Not built, not tested - best effort |
| Other 1.21.x | Paper, Folia | 21 | Not built, not tested - best effort |

Builds are produced for the pinned `minecraft` line only (see
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml)); there is no
per-version build profile for older lines, so those rows cannot be built or
boot-tested even if you wanted to.

Legend: a green CI smoke means the built plugin boots on a real server,
enables cleanly with storage configured, answers `/continuum status` over RCON,
and logs no `SEVERE`. It does not mean every feature works under load - see
the previous section.

This table is maintained by hand and changes together with the matrix in
`.github/workflows/compat-ci.yml`. When a new Minecraft release ships, the
scheduled detector opens an issue (label `area:ci`) to track the pin bump, the
`api-version` bump, and this table.

## Older versions and the "support window"

For Minecraft 1.21.x and earlier:

- They are **not supported**, in the sense that CI does not build or boot
  them, no release is produced for them, and version-specific bug reports
  may be closed as out of scope.
- "Best effort" means: if the fix is trivial and does not compromise the
  always-latest line, it may be taken. There is no promise it will be, and no
  timeline.
- Once Paper/Folia themselves drop a line (end of life upstream), downstream
  support is impossible by definition and will not be offered.

"Support window" for Continuum means the **current always-latest release
line only** - today, 26.3. When Mojang ships the next release, the pin moves
after that line is buildable and smoke-tested, and the previous line falls
out of the window.

## Reporting version-specific bugs

Open a [bug report](https://github.com/CandyriyaMC/Continuum/issues/new/choose)
and include:

- The **Minecraft version and platform**, including the server build number
  (logged at startup, e.g. `paper-26.3-256`).
- The **plugin version** (`/continuum version`, or `build-<N>` / `1.0.0` for
  jars you built yourself).
- The **full `server.log`** (trim the world-gen spam if you must, but keep
  everything Continuum-related and the boot sequence).
- Whether storage was reachable (the plugin applies migrations at boot and
  refuses to serve when they fail - migration errors are bugs, attach them).
- For anything that smells like corrupt player state: whether it reproduces
  on a fresh profile, and the exact sequence of logins/transfers.

Labels: `area:paper` for backend issues, `area:velocity` for proxy issues,
`area:storage` / `area:messaging` for persistence and transport, `area:ci`
for this workflow itself. Security reports go through the
[security policy](../.github/SECURITY.md), not public issues.

## Descriptor and API versioning

The plugin declares `api-version: '26.3'` in
[`paper-plugin.yml`](../paper/src/main/resources/paper-plugin.yml). Paper uses
this to decide how much backwards-compatibility behavior to apply; a
mismatch between the descriptor and the server is a build/configuration error,
not something to work around at runtime. Any bump of the Minecraft line must
bump `api-version` there in the same change, along with `minecraft` in the
version catalog and the matrix in this file and the workflow.
