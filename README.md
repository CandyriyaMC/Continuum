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
