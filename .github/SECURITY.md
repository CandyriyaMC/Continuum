# Security Policy

## Reporting a vulnerability

**Never open a public issue, discussion or pull request for a security vulnerability.**
It exposes the flaw to everyone before a fix exists.

Use GitHub's **Private Vulnerability Reporting** — the ["Security" tab → "Report a vulnerability"](https://github.com/CandyriyaMC/Continuum/security/advisories/new). This is the preferred and fastest channel.

Please include, as far as you can:

- the affected Continuum version (tag, commit or build) and your platform (Paper / Folia / Velocity),
- the type of vulnerability and its impact (who can do what to whom),
- reproduction steps or a proof of concept,
- any suggested fix, if you have one.

## What counts as a vulnerability here

Examples specific to Continuum — report these privately:

- One player's state (inventory, ender chest, experience, …) restored into another player's session,
- session hijacking or spoofing between a backend and the Velocity proxy (plugin messaging, auth handshake),
- privilege escalation via commands, permissions or API endpoints exposed by the plugin,
- leaks of database credentials or snapshot data (logs, errors, config dumps),
- injection (SQL, message-queue) through any configurable or player-influenced input.

Not vulnerabilities — ask in [Discussions](https://github.com/CandyriyaMC/Continuum/discussions) instead:

- "Griefing is possible on our server" — that's a permissions/config problem,
- bugs in Minecraft, Paper, Folia or Velocity themselves — report upstream to those projects,
- theoretical issues with no practical impact.

## What to expect

- Acknowledgment within ~7 days (best effort — this is a community-run project, not a 24/7 team).
- We'll confirm the report, ask for missing details, and fix on a private branch.
- Public disclosure happens when a fix is released, or after a coordinated deadline (typically 90 days, sooner by agreement).
- There is no bug bounty — this project is free software built by a single maintainer. If you'd like, we'll credit you in the release notes.

## Supported versions

| Version | Supported |
| ------- | --------- |
| Latest release (`master`) | ✅ security fixes |
| Older releases and forks | ❌ unless explicitly documented |

## Scope notes

Upstream projects (Minecraft, Paper, Folia, Velocity, database drivers, RabbitMQ client, …) carry their own security policies — report vulnerabilities in them to those projects, not to us. Configuration mistakes that need no code change are support questions, not vulnerabilities.
