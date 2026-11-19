# Continuum

[![build](https://github.com/CandyriyaMC/Continuum/actions/workflows/build.yml/badge.svg?branch=dev)](https://github.com/CandyriyaMC/Continuum/actions/workflows/build.yml)
[![release](https://img.shields.io/badge/release-master-blue)](https://github.com/CandyriyaMC/Continuum/releases)
[![license: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)

**Your players should stay *themselves* everywhere.**

Continuum is a distributed, production-grade infrastructure plugin for Minecraft networks.
It keeps the full player state — inventory, ender chest, vitals, experience, effects,
attributes, advancements, statistics, recipes, persistent data and custom plugin data —
synchronized across backends, so a player can hop between servers without a single item
or achievement getting lost.

- [Velocity](https://velocitypowered.com/) coordinates the network sessions.
- [Paper](https://papermc.io/) and [Folia](https://papermc.io/software/folia) backends capture and restore player state.
- MySQL / MariaDB / MongoDB are the source of truth, with fencing tokens so stale servers can never overwrite newer data.
- RabbitMQ delivers durable, asynchronous events; Plugin Messaging handles fast proxy-backend traffic.

Currently in active development. See the repository for status and releases.

Supported platforms and the always-latest compatibility policy live in
[docs/compatibility.md](docs/compatibility.md). Ready-made builds are published from
`master` merges as `build-<N>` releases; see [docs/release.md](docs/release.md) for how
releases are cut and [docs/licensing.md](docs/licensing.md) for what the free source,
the $3.99 jar and support each mean for you.

## Documentation

| Doc | What it covers |
| --- | --- |
| [docs/development.md](docs/development.md) | Working on Continuum: build composition, tests, local compat smoke |
| [docs/README.md](docs/README.md) | Index of every document in this folder |
| [docs/installation.md](docs/installation.md) | Deploying Continuum: requirements, Velocity/backend/database setup, checklist |
| [docs/compatibility.md](docs/compatibility.md) | Supported versions, the CI smoke matrix, support policy |
| [docs/licensing.md](docs/licensing.md) | The license model, buying, activating, refunds, AGPL terms |
| [docs/release.md](docs/release.md) | How releases are cut (`dev` → `master`, `build-<N>`, source-only) |
| [docs/updater.md](docs/updater.md) | The in-plugin updater: protocol, fail-open behavior, key provisioning |
| [docs/ci-tags.md](docs/ci-tags.md) | Commit tags that shape CI (`[compat skip]`, `[release notes]`, …) |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to contribute: builds, tests, branches, commit style |
| [SECURITY.md](.github/SECURITY.md) | Reporting vulnerabilities privately |
| [SUPPORT.md](.github/SUPPORT.md) | Where to get help |

## Privacy & metrics

Continuum ships with optional, anonymous [bStats](https://bstats.org) metrics. What
that means, exactly:

- **What is collected** (when metrics are on): the number of servers running the
  plugin, a version distribution, and three configuration values — the storage
  engine in use (MySQL/MariaDB/MongoDB), whether a custom restore order is pinned,
  and which synchronization modules are toggled on or off.
- **What is never collected:** player names, UUIDs, IP addresses, chat, skins,
  inventories, locations, or any other personal or player data. Ever. Not hashed,
  not "anonymized", not at all. The custom charts report fixed configuration values
  only; bStats itself adds the server count and version distribution.
- **Not active yet.** The bStats service id is assigned when the plugin is
  registered on bstats.org at first release. Until that happens, the metrics code
  in this repository is inert: no HTTP requests of any kind leave the process.
- **Opting out is always free and always possible.** Per node: set
  `metrics.enabled: false` in `platform.yml` on Paper backends, or `enabled: false`
  in `metrics.yml` on the Velocity proxy (that file is seeded with `enabled: true`).
  For every plugin at once: set `enabled: false` in bStats' global config
  (`plugins/bStats/config.yml` on Paper, `plugins/bStats/config.txt` on Velocity).
  Continuum never reads, overrides or works around the global opt-out.
- **PlaceholderAPI** (optional soft integration on Paper): if you have
  PlaceholderAPI installed, Continuum registers an internal expansion exposing
  `%continuum_version%`, `%continuum_modules_enabled%`, `%continuum_role%` and
  `%continuum_restore_order%` — all derived from the shared configuration or the
  plugin descriptor, identical for every player, containing nothing personal. If
  PlaceholderAPI is absent, the integration does nothing and the plugin enables
  exactly as usual. It is never published to the eCloud store.
- The full privacy statement for the project's updater and license service lives in
  the licensing document; the same rules apply there — no player data, no exceptions.

## License & support

Continuum is free software, licensed under the
[GNU Affero General Public License v3.0](LICENSE) (or, at your option, any later version).
The full source code is here for everyone — build it yourself for free, that's the whole
point of the license.

And since this is a real person writing this, not a corporation:

- **Self-builders ride free.** If you're comfortable running `./gradlew build`, you never
  have to pay a cent. That's not a loophole, that's the deal.
- **But maybe you just want the jar?** Say you run a server — fifty, sixty players, a real
  community — and the last thing you want to do after a long day is fight a Gradle build.
  Totally understandable. Write to [@foliaxs](https://t.me/foliaxs) on Telegram, be a human,
  say something like "hey, I'd love to use this but I can't afford it right now — could you
  spare the jar?", and there's a very good chance you walk away with the jar and a smile.
  I'll be genuinely glad that you trusted me with your server.
- **Need a fix?** Same story. Ask like a civilized person — "could you please look at this,
  here's the log" — and I'll happily dig in and fix whatever is actually broken. The only
  mode that doesn't work is "эй, fix this garbage" delivered with the assumption that I'm
  an unpaid on-call dev for your server. I'm not. But a friendly ask works wonders.
- **The $3.99 is a tip jar, not a toll booth.** Once sales are set up, a ready-made build
  plus support and updates will cost about four dollars. If you pay it, you get the jar,
  updates and my attention — and a very happy student who can eat for a few days. If you
  don't, you get exactly the same plugin, because it's AGPL and I'm not interested in
  holding functionality hostage.
- **Either way, please don't run "leaked" builds.** If you find a cracked copy of this
  plugin floating around, do not install it. I can't verify what's inside it, and neither
  can you. Building from source takes five minutes and always works — that's the honest
  path.
- **What the $4 buys me:** a few days of food and a few evenings of development instead of
  staring at my account balance deciding whether coffee is a luxury today. Not much. But
  it's real, and it's the reason this project exists.

So: build it, ask me, throw a few dollars my way if you feel like it — whatever works for
you. Just don't install mystery jars from the internet, your players deserve better
(you deserve better too).

No corporate hotlines, no "enterprise" tier, no twenty-page pricing table. Just a student,
a Telegram chat, and however many dollars you're comfortable with — or zero, and that's
fine too.

## Links

- Organization: [github.com/CandyriyaMC](https://github.com/CandyriyaMC)
- License: [LICENSE](LICENSE) — Copyright (C) 2026 CandyriyaMC and contributors
