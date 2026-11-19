# Development

What working on Continuum looks like: how the build is put together, where the
tests live, how to run the compatibility smoke locally, and the things that
have bitten people before. For branch flow, PR rules and commit style, see
[CONTRIBUTING.md](../CONTRIBUTING.md).

## How the build is composed

One Gradle build, six modules plus a small `tools/` directory. The root
`build.gradle.kts` applies the shared conventions (Java 25 toolchain, UTF-8,
JUnit platform); each module has its own `build.gradle.kts`.

| Module | What it is |
|---|---|
| `:api` | The public API surface (`lol.candyriya.continuum.api`): events, services, snapshots, module contracts. No runtime dependencies beyond annotations — platform-agnostic by design. |
| `:common` | Shared logic every platform uses: config loading, i18n, the wire protocol, session/snapshot models, recovery, scheduler abstractions. |
| `:storage` | Persistence: SQL (MySQL 8 / MariaDB) and MongoDB backends, the outbox, schema migrations. |
| `:messaging` | The event transport: RabbitMQ (confirms, DLQ), plugin messaging, outbox wire records, idempotency. |
| `:paper` | The backend adapter for Paper and Folia: sync modules, orchestration, gates, schedulers. Builds the shaded `continuum-paper-*.jar`. |
| `:velocity` | The proxy adapter: sessions, transfer/handoff orchestration, commands. Builds the shaded `continuum-velocity-*.jar`. |

`tools/ci/` holds the CI tag parser (`tags.py`) and its tests — the same
parser the workflows and `ci-tags-lint` use.

Both platform jars are assembled with the Shadow plugin; bundled third-party
code is relocated under `lol.candyriya.continuum.libs.*`. `gradle.properties`
pins the local development version (`1.0.0`); CI always passes
`-Pversion=build-<N>`, which wins, and both descriptors are generated from it.

## Where the tests live

Every module keeps its tests in `<module>/src/test/java`, next to the sources
they cover. They are JUnit 6 and are written against public interfaces, not
implementation details — if you find yourself reaching into a private class,
the design is telling you something.

## Running the tests

```bash
./gradlew test
```

That covers everything on a machine with no docker: the unit tests simply run.
The container-backed integration tests are the exception:

- `storage` — MySQL, MariaDB and MongoDB (incl. the outbox claimer tests)
- `messaging` — RabbitMQ

These use [Testcontainers](https://testcontainers.com/) and start real
services. Each of them checks
`Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), …)`
up front, so on a dockerless machine they **skip cleanly — skipped tests, not
failures**. Don't be fooled by a green run here: CI runs with docker and
executes them for real, and if you touch storage or messaging you should too.

```bash
./gradlew :storage:test :messaging:test   # with docker running
```

## Running the compat smoke locally

The compatibility smoke is what CI runs on every PR: build the plugin, boot it
on a real Paper and a real Folia server against a real MySQL 8, then probe it
over RCON. The authoritative recipe lives in
[`.github/workflows/compat-ci.yml`](../.github/workflows/compat-ci.yml) — the
steps below are a summary, follow the workflow file when the two drift. One
leg per matrix entry (today: 26.3 on Paper and Folia):

1. Build the plugin: `./gradlew :paper:shadowJar -Pversion=build-compat`
2. Resolve the newest **STABLE** build of the pinned Minecraft line from Fill
   v3 (`fill.papermc.io`), verify the SHA-256, and refuse non-STABLE channels.
3. Stage a `smoke/` directory: the server jar, `eula=true`, and the plugin in
   `plugins/`, with a smoke `config.yml` pointing storage at MySQL 8
   (`localhost:3306`, database `continuum_smoke`). The workflow file contains
   the full config.
4. Boot it (`java -Xms512M -Xmx2G -jar server.jar nogui`), wait for the
   `Done (` line — 300 s timeout, `server.log` is kept on failure.
5. Assertions: no `SEVERE` anywhere, the plugin enabled, the bootstrap line
   (`Commands registered at the bootstrap stage`) is in the log.
6. Probe `/continuum status` over RCON (rcon-cli); the reply must mention
   Continuum and must not be an unknown-command response.

If a boot fails locally, read `smoke/server.log` — the workflow uploads it as
an artifact on failure for the same reason. The policy behind the smoke (what
it does and does not prove) is in [compatibility.md](compatibility.md).

## Dependency policy

All versions live in the version catalog (`gradle/libs.versions.toml`). No
hardcoded versions in `build.gradle.kts` files, no version ranges, no
snapshots or RCs — latest stable only, on purpose. If you need a new
dependency, add it to the catalog.

Two known exceptions to the catalog-only rule:

- `me.clip:placeholderapi:2.12.3` is a compile-only dependency in
  `paper/build.gradle.kts` (and the matching test dependency), not in the
  catalog — it is only published to the HelpChat repository. Keep it in sync
  by hand until it gets moved over; don't copy the pattern.
- the Foojay toolchain resolver plugin is applied in `settings.gradle.kts`
  with an inline version (`1.0.0`). That file has no access to the catalog;
  the version is pinned there. If you bump it, bump it there.

## Common pitfalls

**Folia vs Paper API capability selection.** `paper-api` and `folia-api` both
ship the `org.bukkit:bukkit` capability and, with the current 26-stable
generation, the `com.destroystokyo.paper:paper-api` capability as well. Gradle
refuses to put two modules providing the same capability in one dependency
graph, so one of them has to win per capability.
`paper/build.gradle.kts` fixes this with
`resolutionStrategy.capabilitiesResolution`: for both capabilities it selects
`dev.folia:folia-api`, because it is the superset surface (the Paper API plus
the regionized scheduler types). The selection in the build file matches on
the module id prefix (`dev.folia:folia-api:`) — do not "fix" it with a version
comparison: the build numbers of the two forks drift independently and any
fixed comparison picks wrongly. At runtime nothing changes: both platforms
provide these APIs, which is why both are `compileOnly` and never bundled.

**Platform APIs are never bundled.** `paper-api`/`folia-api`/`velocity-api`
are `compileOnly`, as is Adventure — both platforms provide all of it.
Bundled third-party code ships relocated under `lol.candyriya.continuum.libs.*`;
classes whose names are part of the resolution contract stay unrelocated on
purpose: JDBC drivers (`com.mysql`, `org.mariadb.jdbc` — `DriverManager`
resolves by class name) and the MongoDB driver (`org.bson`, `com.mongodb` —
registers via `META-INF/services`). The full list, including what is excluded
from the jar entirely, is documented in `paper/build.gradle.kts`.

**Adding a schema migration.** Migrations are explicit, not scanned from the
classpath. Add the `VNNN__<name>.sql` resource in
`storage/src/main/resources/db/migration/` **and** one line in
`Migrations.KNOWN` in
`storage/src/main/java/lol/candyriya/continuum/storage/sql/Migrations.java`,
or the migration silently does not exist. Checksums are validated at boot; an
applied migration is never edited — write a new one instead. On failure,
storage refuses to serve.

**Versions in resource filtering.** `processResources` for `paper-plugin.yml`
uses `expand()` and only plain ASCII is safe in that file; the task sets
`filteringCharset = "UTF-8"`, but non-ASCII in `paper-plugin.yml` will still
surprise you.

**Green-but-skipped container tests.** If docker is missing, the integration
tests report as skipped and the build is green. Check the skipped count
before assuming coverage — or just install docker.

**`-Pversion=` beats `gradle.properties`.** Local builds say `1.0.0`; CI
builds say `build-<N>`. If a jar you built locally reports `1.0.0`, that is
why.

## The public API freeze

Everything in `lol.candyriya.continuum.api` (the `:api` module) is public API —
it is what other plugins will code against. Treating it as frozen does not
mean it never changes, it means:

- additive changes (new events, new services, new types) are fine in a normal PR;
- anything that changes or removes an existing signature, field or constant needs
  an issue discussion first — open it before writing code, not after the review.

The internal modules (`common`, `storage`, `messaging`, `paper`, `velocity`)
are not public API and carry no such promise.

## How dependencies are updated

Dependency updates arrive automatically once a week, driven by the config in
`.github/dependabot.yml`. Gradle (and the wrapper) and GitHub Actions are
covered. Minor and patch bumps are batched into grouped PRs and are routine;
major bumps stay as individual PRs and need a human review — Gradle plugins
(Shadow, run-paper/run-velocity, the Foojay resolver) in particular. The
platform APIs (Paper, Folia, Velocity) move in lockstep with Minecraft
releases and are deliberately kept in a single PR.

If you find a dependency pinned for a good reason, say so in a comment next to
the version in `gradle/libs.versions.toml` — otherwise the next update PR and
the next reviewer have to rediscover it from scratch.
