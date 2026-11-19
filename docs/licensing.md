# Licensing, purchasing and support

## The EULA in one paragraph (TL;DR)

Continuum is free software under the GNU Affero General Public License v3.0.
Everything it does, it does for free, forever: read the source, build it, run
it, self-host it, modify it, ask to be spared the $3.99 — all legitimate, all
welcome. The ready-made build costs $3.99 because some people would rather not
fight a Gradle build after a long day, not because permission is for sale.
Fork all you like, but your fork must remain AGPL and must not pretend to be
official. Support is best effort from one human, not a helpdesk. The software
comes with no warranty of any kind (AGPL section 15) and no liability of any
kind (AGPL section 16). The update and license service, when it ships, exists
to deliver builds to people who paid — never to stop anyone's server from
working. If anything on this page ever conflicts with the
[LICENSE](../LICENSE) file, the LICENSE file wins.

> **Where things stand (November 2026).** The source is public and builds
> today; the purchase side — marketplace listings, the in-plugin updater and
> the license service behind it — is being set up and is not in any released
> build yet. The sections on buying, activation and privacy describe that
> experience as it is being built. Where a command, config key or date is not
> fixed yet, this page says so instead of inventing one. What is true today is
> marked as today; what arrives with the first paid release is marked as such.

## The deal

Continuum is licensed under the GNU Affero General Public License v3.0 (or,
at your option, any later version). That is not a courtesy extended to you, it
is the actual license, and it means:

- The source code is free for anyone to read, study, build and run.
- Building it yourself costs nothing, today and forever.
- Running it — on one server or fifty, at home or for a network with real
  revenue — costs nothing. The AGPL does not charge royalties, and neither do
  we.
- If you modify it and distribute it, the AGPL governs what you do with it
  (see [Terms that matter](#terms-that-matter)).

The $3.99 exists for one reason: some people run servers, not build pipelines.
It buys you a ready-made jar, updates delivered to your server, and the
attention of the person who wrote the plugin. It is a tip jar, not a toll
booth. If you pay it you get the jar and a very happy maintainer; if you don't,
you get exactly the same plugin, because it is AGPL and holding functionality
hostage is not a thing this project does.

## What the $3.99 buys

A one-time purchase — not a subscription, not a monthly fee:

- **A ready-made build.** Official Paper and Velocity jars, built from a
  tagged release of the official repository, published by the release
  pipeline. Not a snapshot someone's laptop produced at 2 a.m.
- **Updates, delivered in-plugin.** When the in-plugin updater ships (see
  below), your server will check
  [`upd.candyriya.xsxs.lol`](https://upd.candyriya.xsxs.lol) for new builds
  and can fetch them for you — no re-downloading zips from a file host every
  month. The check is designed to fail open: if our service is unreachable,
  nothing happens to your server (see
  [Privacy](#privacy)).
- **Support, best effort, from a human.** Discord
  ([discord.gg/fXwN4wETF9](https://discord.gg/fXwN4wETF9)), Telegram
  ([@foliaxs](https://t.me/foliaxs)), or the GitHub issue tracker for
  reproducible bugs. Response times vary — this is one person's evenings, not
  a support desk. Logs and version numbers make helping you much faster;
  "it broke, fix it" with no context helps no one.
- **Updates while they exist.** As new builds are published, they arrive
  through the updater you paid for. If the project or its update service ever
  ends, your server keeps working exactly as before (fail-open), and the
  source remains free forever — rebuilding from source will always be the
  honest fallback.

## What the $3.99 does not buy

- **No SLA.** No guaranteed response time, no uptime commitment for the
  license service, no "we will fix your server by Friday". The one guarantee
  that exists is structural: our service can never take your server down with
  it.
- **No warranty.** The program is provided "AS IS", per AGPL section 15
  (see [Terms that matter](#terms-that-matter)).
- **No support for forks or heavily modified builds.** Official builds,
  installed per the docs, on supported server versions — yes. Your custom
  patches — best effort at best, excluded from the purchase at worst.
- **No promised roadmap.** Features ship when they are ready, or not. Asking
  politely influences this more than it should but less than it deserves.
- **No exclusivity, no Pro tier.** The AGPL applies to every build, including
  the paid one. Nothing is hidden behind the paywall that isn't in the source.
- **No license to the name.** See [Terms that matter](#terms-that-matter).

## Where to buy, and how it works

Continuum will be sold as a one-time $3.99 purchase on community marketplaces:

- [BuiltByBit](https://builtbybit.com)
- [Voxel Shop](https://voxel.shop) (formerly Polymart)
- [SpigotMC](https://www.spigotmc.org)

Listings are being set up and are not live at the time of writing; until they
are, the only way to run Continuum is to build it from source — free, five
minutes, as always. Once listings are live, the purchase flow works like this
(the exact command syntax and config keys will be confirmed in the release
that ships it):

1. **Buy** on any of the storefronts. The marketplace handles payment, taxes
   and its own receipts.
2. **Receive a license token.** The marketplace hands you a purchase token;
   the license service validates it against the marketplace's API and issues
   your `ctm_live_...` token. Raw tokens are never stored on our side — only a
   hash.
3. **Redeem in-game.** In your server console or chat:
   `/continuum license redeem <token>` — the service confirms the purchase and
   links your copy to your order. Updates are then enabled for your build.
4. **Check status** any time with `/continuum license check` — shows the
   license state and whether updates are active.

These two commands are the planned command surface and are **not in any
released build yet** — the codebase is pre-release. The exact syntax will be
documented in the release notes, and this page will be updated with it. Do
not rely on the literal words above until a release says so.

Practical notes on the flow, as designed:

- **Where the token lives.** The plan is for it to live in your Continuum
  configuration, written when you redeem. The exact key will be documented
  with the updater release; it does not exist in the config today. Treat the
  token like a password: never paste it into issues, Discord or screenshots.
- **Moving hosts / reinstalling.** One license covers a small number of
  servers — designed with a default limit of 3. Moving hosts is a re-point
  operation, not a re-purchase: a license reset flow (self-service or via
  @foliaxs) will re-bind the token to your new servers. The limit and the
  reset mechanism are designed defaults, not yet shipped; details land with
  the release.
- **SpigotMC** has no purchase-verification API, so activation there may need
  a manual step (a coupon or a chat with @foliaxs). If your activation stalls,
  ping @foliaxs with your order reference.
- **Fail-open, always.** If `upd.candyriya.xsxs.lol` is down, unreachable, or
  decommissioned, the license and update checks do nothing. Your server
  starts, your players play, your state keeps syncing. The only thing you
  lose is update delivery until the service is back — which, if it were ever
  permanent, would degrade to "rebuild from source", never to "server dead".

## Terms that matter

1. **The license is AGPL-3.0.** Full text in [LICENSE](../LICENSE). If you
   convey a modified version of Continuum — to anyone, for any price or for
   free — AGPL section 5 requires you to license the whole work,
   modifications included, under the AGPL, and to provide the corresponding
   source.
2. **Network use is not a loophole.** If you run a *modified* Continuum and
   let users interact with it over a network (players connecting to your
   server), AGPL section 13 requires you to offer those users the
   corresponding source. Note the trigger is *modification* — running an
   unmodified Continuum, even the $3.99 build, even on a commercial network,
   creates no source-offer obligation for your own code or config.
3. **Buying does not restrict you, and you cannot restrict others.** Your
   copy comes with the full AGPL rights (section 10), and so does every copy
   downstream of yours. There is no EULA here that takes the AGPL away, and
   you cannot attach one.
4. **No payment is ever required.** Self-building, modifying, forking,
   self-hosting, running commercially — none of these obligate you to pay or
   to ask. The $3.99 is for people who want the convenience, nothing else.
5. **The name is not for sale.** "Continuum" and "CandyriyaMC" identify this
   project and the organization behind it. Forks, ports and derivatives must
   use their own name and may not present themselves as official, as the same
   project, or as endorsed by CandyriyaMC — on marketplaces, Discord servers,
   repositories or domains. Saying "based on Continuum by CandyriyaMC" with
   honest attribution is welcome.
6. **Do not install leaked or re-uploaded builds.** If a "Continuum" jar
   appears on some file host or in a texture pack bundle, nobody can verify
   what is inside it — not you, not the maintainer. Building from source
   takes five minutes and always works; that is the honest path, and
   "leaked" builds are treated as untrusted, not as warez to be admired.
7. **No warranty, no liability.** The program is provided "AS IS", without
   warranty of any kind, express or implied, including merchantability and
   fitness for a particular purpose — AGPL section 15. In no event is any
   copyright holder or contributor liable for damages of any kind, including
   general, special, incidental or consequential damages (lost data, lost
   revenue, interrupted service) — AGPL section 16.
   **Keep independent backups of your database.** Continuum exists to move
   your players' data around; a backup schedule is not optional, and nothing
   on this page substitutes for one.
8. **Scope of support.** Support covers the official builds of Continuum,
   installed per the documentation, on supported server versions. Custom
   forks, rewired internals and vendored forks of the codebase are outside
   the purchase; pull requests to fix what is actually broken are always
   welcome, with or without money involved.
9. **Pricing and terms may change prospectively.** The price and these terms
   apply as published at the time of purchase; changes never retroactively
   affect copies already paid for.
10. **This page is not legal advice and not a substitute for the AGPL.** The
    license text controls. If you need legal certainty for a commercial
    deployment, talk to a lawyer, not to a README.

## Privacy

**Today:** Continuum does not phone home. There is no license check, update
beacon or metrics code in the current source tree — nothing to disclose,
nothing to disable.

**When the updater and license service ship, as designed:**

- The update check sends exactly this: your build identifier (e.g.
  `build-<N>`), your Minecraft version, your Java major version, whether you
  are on Folia, your channel (`release` or `dev`), and your license token in
  the authorization header.
- The service stores: a SHA-256 hash of your token (never the token itself),
  the marketplace and order reference (needed for refunds and revocations),
  whether the license is revoked, and timestamps of checks and downloads.
  Records expire automatically — the design targets a 90-day retention, after
  which they are deleted.
- Anonymous downloads of development builds are counted without any token, so
  the project can see how many servers run which build.
- **No player data, ever.** No player names, UUIDs, chat, skins, inventories,
  locations or IP addresses are collected by the license/update service — not
  "anonymized", not "hashed", not at all. Server operators are the only
  humans the service ever hears from.
- **Fail-open.** See [Where to buy](#where-to-buy-and-how-it-works). The
  service going down changes nothing about your server starting or your
  players playing.
- **Opting out entirely.** Until the updater ships there is nothing to
  opt out of. When it ships, it will include a documented opt-out switch in
  the config; the exact key will be published here and in the release notes
  at the same time — until then, any key you might see quoted elsewhere does
  not exist. You can also block outbound traffic to
  `upd.candyriya.xsxs.lol` at your firewall without affecting gameplay, and
  builders of the open-source code run the same updater with the same
  controls. There is no second, hidden binary.
- **bStats.** Anonymous server-count metrics (distinct servers running the
  plugin, version distribution — no player data) are being considered for a
  later release, with an explicit opt-out in the config and a disclosure
  update to this page *before* it ships. As of this writing, it is not
  present.

## Refunds, chargebacks and abuse

- **14 days, no questions.** Full refund within 14 days of purchase. Message
  @foliaxs on Telegram or bejiihiu@icloud.com with your order reference — or
  use the refund flow of the storefront you bought from. No forms, no guilt
  trip, no survey.
- **After 14 days: ask anyway.** If the jar does not run on a supported
  platform and we cannot fix it, you will get your money back. "I changed my
  mind after three months" is not an automatic refund, but expect a friendly
  human conversation, not a refusal machine — $3.99 is not worth bad faith in
  either direction.
- **Marketplace refunds.** BuiltByBit, Voxel Shop and SpigotMC each process
  refunds through their own systems with their own timelines. Using those is
  always fine and needs no permission from us — please do tell us anyway, so
  we can keep our records honest and possibly fix whatever drove you away.
- **Chargebacks, please don't make them your first move.** If the payment was
  wrong or the jar never arrived, contact us first — a message almost always
  solves it faster than a bank dispute, and without the dispute fee a tiny
  project pays on both sides. A chargeback reverses the purchase, and a
  reversed purchase means the license token is revoked: the token entitles
  nothing if the payment went back. Revocation stops update delivery only —
  it never stops your server.
- **Abuse.** Re-uploaded "official" jars, resold licenses, support used as a
  personal on-call after a refund — report to @foliaxs or
  bejiihiu@icloud.com. Marketplace listing violations get reported to the
  marketplace. Security vulnerabilities: private reporting only, see
  [SECURITY.md](../.github/SECURITY.md); never a public issue.

## Why so cheap

Because it's a tip jar, not a pricing strategy. Continuum exists because a
student wanted to build the thing properly and have it be free for people who
can't pay. Four dollars is what one decent lunch costs; it is not a rent
harvest, and there is no $19 tier hiding behind it. What the $3.99
buys the maintainer is a few evenings of development instead of an account
balance staring contest. What it buys you is a ready-made jar, updates, and
the small pleasure of knowing your community runs open software paid for by
gratitude instead of lock-in. If you can't pay, ask — the answer has a very
good chance of being the jar and a smile. If you can pay, thank you. Either
way, don't run mystery jars, and go build something.

## Not affiliated

Continuum is an independent, community-run project. It is not affiliated
with, endorsed by, sponsored by or connected to Mojang Studios, Microsoft,
PaperMC, the Folia or Velocity projects, or any server host — it is a plugin
that works with those platforms, that is all. Minecraft is a trademark of
Mojang Synergies AB; Paper, Folia and Velocity are projects of PaperMC. No
claim is made to those names, and none should be inferred.

## Contacts and pointers

- **Buying / license activation / refunds:** @foliaxs (Telegram),
  bejiihiu@icloud.com
- **Support:** Discord ([discord.gg/fXwN4wETF9](https://discord.gg/fXwN4wETF9)),
  GitHub Discussions for usage questions, the issue tracker for bugs
- **Security reports:** [SECURITY.md](../.github/SECURITY.md) — private only
- **The license:** [LICENSE](../LICENSE) (AGPL-3.0)
- **The project:** [README](../README.md),
  [CONTRIBUTING.md](../CONTRIBUTING.md)
