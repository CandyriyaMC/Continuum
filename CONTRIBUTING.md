# Contributing to Continuum

Thanks for wanting to help! This document applies to this repository. If anything here is unclear, ask in [Discussions](https://github.com/CandyriyaMC/Continuum/discussions) before you invest time — a five-minute question beats a rewrite.

## Ground rules

- Be kind and constructive — see [CODE_OF_CONDUCT.md](.github/CODE_OF_CONDUCT.md).
- **Security issues are never filed as public issues.** Report privately via the [security policy](.github/SECURITY.md).
- Never commit secrets (tokens, database passwords, plugin messaging keys). If in doubt, leave it out.
- In-code comments, identifiers and commit messages are written in English. User-facing text (messages, docs, config comments) is English and is expected to read professionally.

## Where to start

- Browse [open issues](https://github.com/CandyriyaMC/Continuum/issues), especially those labeled `good first issue` or `help wanted`.
- Have a question instead of a bug? Ask in [Discussions](https://github.com/CandyriyaMC/Continuum/discussions) first.
- Found a bug? Open one using the **Bug report** form and include versions, platform and logs (redact anything private).
- Looking to add something big? Open a feature request or a discussion first, so the design can be agreed before you write code.

## Workflow

1. Fork the repository and create a branch from `dev` — the integration branch:
   `feature/<name>`, `fix/<name>`, `docs/<name>` or `chore/<name>`.
2. Use [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `perf:`, `chore:`.
   One logical change = one commit. Subject max 72 characters, body explains *why*.
3. Keep pull requests focused — one concern per PR.
4. Fill in the pull request template. CI must be green.

`master` is the default, protected branch. Day-to-day work happens in `dev`, the integration branch — please target your pull requests at `dev`, not `master`.

## What CI expects

- `./gradlew build` on Java 25 (temurin; the toolchain in `build.gradle.kts` pins it):
  compiles everything, runs the tests, and produces both shaded jars.
- Dev servers for manual verification: `./gradlew :paper:runServer`, `./gradlew :velocity:runVelocity`.

## Style notes

- Java 25 idioms (records, sealed types, pattern matching, `var`) where they fit. No `--enable-preview` in shipping code — server runtimes won't enable it and the jar won't start.
- Shared logic lives in `common`; platform-specific code stays in the `paper` and `velocity` adapters. Platform APIs (Paper, Folia, Velocity) are called on their own threads only.
- Blocking I/O (database, RabbitMQ, serialization) never runs on game threads.
- User input in MiniMessage always goes through `Placeholder.unparsed` / `Placeholder.parsed`, never string concatenation.
- Tests go through public interfaces (JUnit 6), not implementation details. If you change public behavior, update the docs in the same PR.
- The config schema is versioned (`config-version`): adding a migration means bumping `ConfigMigrations.CURRENT_VERSION` and appending `addVersion`/`makeVersion` entries. Old files migrate themselves — never ship a breaking change without one.
- Code comments are short and direct — explain *why*, not *what*.

## Review process

A maintainer will review your PR. Reviews are about the code, not the person — please don't take direct feedback personally. Re-request review after addressing comments; expect the occasional round of iteration, that's normal.

## License

Continuum is AGPL-3.0. By contributing, you agree your contributions are licensed under the same terms.
