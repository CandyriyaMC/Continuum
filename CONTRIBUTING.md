# Contributing to Continuum

Thanks for wanting to help! This document applies to this repository. If anything here is unclear, ask in [Discussions](https://github.com/CandyriyaMC/Continuum/discussions) before you invest time — a five-minute question beats a rewrite.

For how the build is put together, where the tests live and what tends to trip people up, see [docs/development.md](docs/development.md). The full docs tree is indexed in [docs/README.md](docs/README.md).

## Ground rules

- Be kind and constructive — see [CODE_OF_CONDUCT.md](.github/CODE_OF_CONDUCT.md).
- **Security issues are never filed as public issues.** Report privately via the [security policy](.github/SECURITY.md).
- Never commit secrets (tokens, database passwords, plugin messaging keys). If in doubt, leave it out.
- In-code comments, identifiers and commit messages are written in English. User-facing text (messages, docs, config comments) is English and is expected to read professionally.

## Where to start

- Browse [open issues](https://github.com/CandyriyaMC/Continuum/issues), especially those labeled `good first issue` or `help wanted`.
- Have a question instead of a bug? Ask in [Discussions](https://github.com/CandyriyaMC/Continuum/discussions) first.
- Found a bug? Open one using the **Bug report** form and include versions, platform and logs (redact anything private).
- Looking to add something big? Open a feature request or a discussion first, so the design can be agreed before you write code. This applies doubly to the `api` module — it is the public API surface, changes there are public API changes, and they go through an issue first.

## Workflow

1. Fork the repository and create a branch from `dev` — the integration branch:
   `feature/<name>`, `fix/<name>`, `docs/<name>` or `chore/<name>`.
2. Use [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `perf:`, `chore:`, `ci:`.
   The scope is the module or area the change touches, lowercase — `feat(storage):`, `fix(paper):`, `docs(release):`, `ci(tags):`. Use the module names (`api`, `common`, `storage`, `messaging`, `paper`, `velocity`) or a short area word (`ci`, `docs`, `updater`, `metrics`). Leave the scope off when it wouldn't add information.
   One logical change = one commit. Subject max 72 characters, body explains *why*.
3. Open your pull request against `dev`. Not `master` — `master` only advances through PRs and normally only for releases cut by the maintainer.
4. Keep pull requests focused — one concern per PR.
5. Fill in the pull request template. The required status check is `build`.
6. PRs land via squash-merge, so history on `dev` stays linear. Push fixups as separate commits on your branch; the squash takes care of the rest.

`dev` is the integration branch — all day-to-day work lands there first. `master` is the protected release branch: it moves only through squash-merged PRs (usually `dev` → `master`), with the `build` check green. Your PR never needs to touch `master`.

## What CI expects

- `./gradlew build --parallel` on Java 25 (temurin; the toolchain in `build.gradle.kts` pins it and the Foojay resolver downloads it if your machine doesn't have it):
  compiles everything, runs the tests, and produces both shaded jars.
- The required status check is `build` (`.github/workflows/build.yml`). `compat-ci` also runs on PRs — it boots the built plugin on a real Paper and a real Folia server against a real MySQL — but `build` is the gate.
- Dev servers for manual verification: `./gradlew :paper:runServer`, `./gradlew :velocity:runVelocity`.

## Tests

- The suite is JUnit 6 and goes through public interfaces, not implementation details.
- `./gradlew test` works on a machine with no docker. The container-backed integration tests — storage (MySQL, MariaDB, MongoDB) and messaging (RabbitMQ, via [Testcontainers](https://testcontainers.com/)) — check `DockerClientFactory.instance().isDockerAvailable()` up front and skip cleanly when docker is missing. You get skipped tests, not failures. CI runs them for real, and so should you if you're touching storage or messaging — install docker.
- If you change public behavior, update the docs in the same PR.

## CI tags

You can put tags like `[docs only]` or `[compat skip]` in commit messages and PR descriptions to shape what CI runs. What you need to know as a contributor:

- Tags never grant rights. No tag triggers a release, skips a security check, or turns a red build green.
- On your PR, the useful tags are the dev/PR ones (`[compat skip]`, `[tests skip]`, `[docs only]`, …). In fork PRs only tier A tags apply; tier B and Emergency tags from a fork are silently dropped.
- On `master`, artifact-shaping tier B tags are ignored — a release always builds in full. The deliberate exceptions are `[hotfix]`, `[release]` and `[no release]`: they exist for the master-side release flow and are honored there, not on your PR.
- Release-related tags are not yours to hand out. There is no tag that makes your change ship.
- Unknown tags are warnings, never silently honored.

The full registry, tiers and syntax live in [docs/ci-tags.md](docs/ci-tags.md) — read it before inventing your own tag.

## Releases

Contributors don't cut releases. Releases are made by the maintainer opening a PR from `dev` to `master` and merging it — see [docs/release.md](docs/release.md) for the whole flow (`build-<N>` versioning, source-only GitHub Releases, publishing to R2 and Discord). Nothing you do in a feature branch pushes a release, a tag or an announcement.

Dependency updates are automated (weekly, see `.github/dependabot.yml`): minor and patch bumps arrive grouped and are routine, major updates come through as individual PRs that need review — Gradle plugins (Shadow, run-paper/run-velocity, foojay) in particular. If you want to help, reviewing an open dependency-update PR is a good place to start.

## Style notes

- Java 25 idioms (records, sealed types, pattern matching, `var`) where they fit. No `--enable-preview` in shipping code — server runtimes won't enable it and the jar won't start.
- Shared logic lives in `common`; platform-specific code stays in the `paper` and `velocity` adapters. Platform APIs (Paper, Folia, Velocity) are called on their own threads only.
- Blocking I/O (database, RabbitMQ, serialization) never runs on game threads.
- User input in MiniMessage always goes through `Placeholder.unparsed` / `Placeholder.parsed`, never string concatenation.
- Storage schema changes are versioned migrations: add a new `VNNN__<name>.sql` in `storage/src/main/resources/db/migration/` plus one line in `Migrations.KNOWN`. Checksums are validated at boot and storage refuses to serve on failure. Never edit a migration that has already shipped.
- The `api` module is frozen-ish: `lol.candyriya.continuum.api` is what other plugins may code against. Additive changes are fine; anything that changes or removes an existing signature gets an issue discussion first, before code.
- Code comments are short and direct — explain *why*, not *what*.

## Where the docs live

User- and operator-facing docs live in [`docs/`](docs/README.md) — installation, compatibility policy, licensing, the release runbook, CI tags, the in-plugin updater, and [docs/development.md](docs/development.md) for this repository. The root [README](README.md) links the important ones. If a change makes a doc wrong, fix the doc in the same PR.

## Review process

A maintainer will review your PR. Reviews are about the code, not the person — please don't take direct feedback personally. Re-request review after addressing comments; expect the occasional round of iteration, that's normal.

## License

Continuum is AGPL-3.0. By contributing, you agree your contributions are licensed under the same terms.
