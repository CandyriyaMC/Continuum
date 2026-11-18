# Continuum

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

## License & support

Continuum is free software, licensed under the
[GNU Affero General Public License v3.0](LICENSE) (or, at your option, any later version).
The full source code is here for everyone — build it yourself for free, that's the whole
point of the license.

That said, a word from a real person:

- **Self-builders ride free.** If you're comfortable running `./gradlew build`, you never
  have to pay a cent. That's not a loophole, that's the deal.
- **$3.99 keeps the lights on.** Once sales are set up, a ready-made build plus support
  and updates will cost about four dollars. Think of it as the price of a coffee with
  worse breath — the build won't judge you at 3 AM when the inventory didn't desync.
- **Can't afford it? Ask nicely.** If you're short on money or just need a hand getting
  things working, ping [@foliaxs](https://t.me/foliaxs) on Telegram and ask. If you ask
  like a human being, there's a very good chance you get help for free. I'm a student,
  not a corporation.
- **Please don't run "leaked" builds.** If you stumble upon a cracked copy of this plugin
  somewhere, please don't install it. I can't verify what's been changed inside it, your
  servers deserve better than a mystery jar, and honestly it's just a worse experience
  than building from source — which takes five minutes and always works.
- **What your $4 does:** it buys me a few days of food and a few evenings of development
  time. Not much, but it's real, and it makes this project exist instead of being another
  dusty repo. So yeah — four dollars, and you live happily with the latest build, fast
  support and updates. I win, you win, everybody wins.

No corporate hotlines, no twenty-tier pricing pages — just a student, a chat, and four
dollars well spent. If you want to support the project beyond that, you know where to
find me.

## Links

- Organization: [github.com/CandyriyaMC](https://github.com/CandyriyaMC)
- License: [LICENSE](LICENSE) — Copyright (C) 2026 CandyriyaMC and contributors
