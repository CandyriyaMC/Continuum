# Installation

This guide walks you through deploying Continuum on a live network: a Velocity
proxy coordinating several Paper/Folia backends, one shared database, and an
optional RabbitMQ broker. By the end you will have every node configured, the
secrets exchanged between proxy and backends, the database created with a
least-privilege user, and a checklist for the first start.

Everything here is written against the current development tree and the first
public release. Continuum is in active development: where a feature is not part
of the current build, this page says so instead of pretending it is. The same
applies to command output — what you see on your console is the source of
truth, this page is the map.

A note on tone before we start: this is a production document, but you do not
need to be a Kubernetes wizard to follow it. If you have run a Paper network
before, the only genuinely new moving parts are the forwarding secret, the
shared database and `network.id`/`network.server-id`. Take them one at a time.

- [Requirements](#requirements)
- [Topology at a glance](#topology-at-a-glance)
- [Step 1 — The shared database](#step-1--the-shared-database)
- [Step 2 — RabbitMQ (optional)](#step-2--rabbitmq-optional)
- [Step 3 — Velocity (the coordinator)](#step-3--velocity-the-coordinator)
- [Step 4 — Backends (Paper or Folia)](#step-4--backends-paper-or-folia)
- [Step 5 — Configuration tour](#step-5--configuration-tour)
- [First-start checklist](#first-start-checklist)
- [Misconfigurations and how they fail](#misconfigurations-and-how-they-fail)
- [Upgrading](#upgrading)
- [Uninstalling](#uninstalling)
- [Links](#links)

---

## Requirements

**Every node** (proxy and backends):

- **Java 25.** The build toolchain is pinned to Java 25 and the plugin
  descriptors target it. Install a Java 25 distribution (Temurin is what CI
  uses) on each node. Older Java will refuse to start the server.
- **A copy of the two Continuum jars.** Either build them yourself —
  `./gradlew build` on Java 25 produces `paper/build/libs/continuum-paper-<version>.jar`
  and `velocity/build/libs/continuum-velocity-<version>.jar` — or use the
  ready-made build. Read [licensing & purchasing](licensing.md) for what the
  ready-made build costs and what it includes. Building from source is free,
  today and forever (AGPL).

**The proxy:**

- **Velocity 4.x** (4.3.1 is the latest stable at the time of writing). Any
  Velocity 4 build works; we test against the current one.

**Each backend:**

- **Paper 26.3+** or **Folia 26.3+**. The Paper plugin declares
  `api-version: '26.3'` and `folia-supported: true`, so the same jar runs on
  both. Folia gets the regionized scheduler; Paper gets the global one.

**Shared state:**

- One of:
  - **MySQL 8.4+** (8.4 LTS is the recommended floor), or
  - **MariaDB 11+** (shares the `mysql` config block; only the driver differs), or
  - **MongoDB 8.0+**, self-hosted or Atlas.
- Optionally **RabbitMQ 3.13 or 4.x** for durable events. Any broker that
  supports quorum queues works. Without it, Continuum runs in a degraded but
  explicit plugin-messaging-only mode — see [Step 2](#step-2--rabbitmq-optional).

**Network:**

- Ports you will want to think about: `25565` (Velocity, public), `25566+`
  (backends, private), `3306` (MySQL) / `27017` (MongoDB), `5672` (AMQP) /
  `5671` (AMQPS), `15672` (RabbitMQ management).
- A private network between the nodes (VLAN, VPN, or a single host) is ideal,
  but not required if you bind services to localhost or private addresses and
  firewall the backend ports — more on that in
  [backend setup](#step-4--backends-paper-or-folia).

---

## Topology at a glance

```
                        players
                           │
                    ┌──────┴──────┐
                    │   Velocity  │   role: coordinator
                    │  (proxy)    │   — routes sessions, owns nothing durable
                    └──────┬──────┘
          modern forwarding│
            + plugin msg   │   continuum:main (frame cap: 65536 bytes)
        ┌──────────────────┼──────────────────┐
        │                  │                  │
 ┌──────┴─────┐     ┌──────┴─────┐     ┌──────┴─────┐
 │  Paper/    │     │  Paper/    │     │  Folia     │   role: backend
 │  Folia #1  │     │  Folia #2  │     │  #3        │   — captures and restores state
 └──────┬─────┘     └──────┬─────┘     └──────┬─────┘
        │                  │                  │
        └──────────┬───────┴──────────────────┘
                   │
            ┌──────┴──────┐      ┌──────────────┐
            │   one DB    │      │  RabbitMQ    │  optional; durable events
            │ MySQL/      │      │  continuum.* │  (transfers, releases)
            │ MariaDB/    │      └──────────────┘
            │ MongoDB     │
            └─────────────┘
```

The pieces and their jobs:

- **One database** holds the shared state: snapshots, sessions, the outbox for
  durable events, and (SQL) the `schema_migrations` bookkeeping table. Every
  node — proxy and backends — points at the *same* database.
- **Velocity coordinates.** It terminates player connections, speaks modern
  forwarding to the backends, and routes transfer traffic over the
  `continuum:main` plugin-message channel. In the current build the proxy does
  no database or broker work at all — boot is deliberately cheap and no login
  ever waits on the network.
- **Backends capture and restore** player state. Each backend keeps its own
  local files in `plugins/Continuum/` (seeded on first run; never overwritten)
  and shares everything network-wide through the database.
- **Plugin messaging** (`continuum:main`) is the fast, connection-scoped
  channel between proxy and backends. It is best-effort and bound to live
  connections.
- **RabbitMQ** carries durable, asynchronous events: transfer handoffs,
  releases. It can be switched off; the system then runs in plugin-messaging
  only, with events queuing in the database outbox until the broker returns.
- **Fencing tokens** in the database guarantee that a stale server that lost
  its session can never overwrite newer player state, no matter what happened
  "before".

Roles in the config decide what a node is, so set them per node:

- Velocity: `network.role: coordinator` — **the bundled default is `backend`,
  and the bundled comment says it plainly: a Velocity deployment must change
  this.** If the proxy boots with `role: backend`, it will behave like one.
- Each backend: `network.role: backend`.
- All nodes: the same `network.id` (e.g. `main-network`), and a unique
  `network.server-id` per node (e.g. `survival-1`, `lobby-1`). Validation
  checks the shape of these values, not their uniqueness on your network —
  duplicate `server-id`s both pass load and then confuse everything downstream.

---

## Step 1 — The shared database

Create the database and the user **before** starting any node. Continuum
creates and migrates its own schema at first boot; it needs rights to do so.

### MySQL / MariaDB (recommended for most networks)

```sql
-- MySQL 8.4 (utf8mb4, accent-insensitive collation)
CREATE DATABASE continuum CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- MariaDB: no 0900_ai_ci — use utf8mb4_unicode_ci instead
-- CREATE DATABASE continuum CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER 'continuum'@'%' IDENTIFIED BY 'use-a-long-random-string';

-- Least privilege, scoped to the continuum schema only. Never GRANT ALL globally.
-- CREATE/ALTER/INDEX/REFERENCES are needed because Continuum applies its own
-- schema migrations at first boot (and refuses to boot half-migrated).
GRANT SELECT, INSERT, UPDATE, DELETE,
      CREATE, ALTER, INDEX, REFERENCES
  ON continuum.* TO 'continuum'@'%';

FLUSH PRIVILEGES;
```

Notes that save you a debugging session:

- **MySQL 8.4 defaults to `caching_sha2_password`. Keep it.** Do not
  downgrade accounts to the removed-or-deprecated old plugins for convenience.
  Caching SHA-2 works out of the box with TLS; over a plaintext connection it
  needs the RSA public key exchange, which is why you may meet the infamous
  `Public Key Retrieval is not allowed` error (see
  [misconfigurations](#misconfigurations-and-how-they-fail)). The right fix in
  production is server-side TLS — e.g. `require_secure_transport=ON` in
  `my.cnf` / `mariadb.cnf` — which Continuum connects through without any
  extra flags. (The current config schema carries no JDBC transport switches;
  TLS is enforced on the server side. If per-connection `verifyIdentity`-style
  options land in the schema later, prefer them in production.)
- **utf8mb4 is not optional.** The migration runner creates
  `schema_migrations` and the rest of the schema with `utf8mb4`; if your
  database/server defaults are latin-1-era, create the schema explicitly as
  shown.
- **Migrations are automatic and all-or-nothing.** On storage start, the
  plugin validates and applies pending `VNNN__name.sql` migrations (currently
  `V001__initial_schema.sql`). Checksums of applied migrations are verified;
  a mismatch (edited migration, unknown applied version) refuses to boot
  rather than serve a half-migrated schema. Take a backup before the first
  boot on an empty database and before every upgrade.
- Point every node at the same database: same host, port, schema name, user.
  The SQL backend keeps a `schema_migrations` table with one row per applied
  migration.

### MongoDB (self-hosted or Atlas)

Create a user with **`readWrite` on the `continuum` database and nothing
else**. That role covers day-to-day operation *and* the first-boot schema
pass (collections, indexes, the fencing-token counter documents) — there is
no need for `dbAdminAnyDatabase`, `userAdmin` or anything global:

```javascript
use continuum
db.createUser({
  user: "continuum",
  pwd: "use-a-long-random-string",
  roles: [ { role: "readWrite", db: "continuum" } ]
})
```

Connection string, two shapes:

- Self-hosted / replica set:
  `mongodb://continuum:<password>@db.internal:27017/continuum`
  (self-hosted MongoDB is happiest as a replica set — even a single-node one —
  so the deployment already looks like production when you scale out)
- Atlas (SRV):
  `mongodb+srv://continuum:<password>@cluster0.example.mongodb.net/continuum?retryWrites=true&w=majority`
  Create the user under **Database Access** with `readWrite` on `continuum`
  only, and add your nodes' IPs to the **Network Access** allowlist — a fresh
  Atlas cluster has no allowlist entries and every connection will time out
  until you add them.

**Atlas M0 is fine for experiments, not for players.** The free tier is a
shared instance with a 512&nbsp;MB cap and no SLA — perfect to kick the tires,
inappropriate for a network people actually play on. For production use at
least a dedicated M10 (or an M-class/replica-set deployment appropriate to
your size) with backups enabled.

MongoDB needs no schema migrations in the SQL sense; the first boot instead
creates the collections and indexes it expects, seeds the fencing-token
counters, and — same philosophy as SQL — refuses to boot if anything is
unexpected rather than serve a half-prepared deployment. A plain
`readWrite` user is enough for that pass.

---

## Step 2 — RabbitMQ (optional)

Skip this section if you want the degraded, plugin-messaging-only mode — and
read [the degraded mode notes](#degraded-mode-messagingrabbitmqenabledfalse)
first so you know what you are choosing.

Otherwise, run a RabbitMQ 3.13 or 4.x broker and create a dedicated vhost and
user. Continuum declares its topology idempotently at boot with arguments —
never policies — so the broker needs *no* `rabbitmqctl` access at runtime:

```
                continuum.events (topic, durable)
                        │  consumers bind with their own patterns
                        ▼
              worker queues (quorum, x-delivery-limit)
                        │ reject / limit exhausted
                        ▼
        continuum.dlx (topic) ──► # ──► continuum.events.dlq (quorum, bounded)

   unroutable from continuum.events
        ▼
   continuum.ae (fanout) ──► continuum.events.ae (durable, bounded)
```

Concrete steps on the broker host:

```bash
# 1. a vhost for Continuum only — its own blast radius
rabbitmqctl add_vhost continuum

# 2. one application user, rights scoped to continuum.* names in that vhost
rabbitmqctl add_user continuum-app 'use-a-long-random-string'

# configure | write | read — all limited to the continuum.* namespace,
# exactly wide enough for the idempotent declarations at boot
rabbitmqctl set_permissions -p continuum continuum-app \
  '^continuum\..*$' '^continuum\..*$' '^continuum\..*$'
```

- **Management UI:** `http://localhost:15672` by default. Keep it bound to
  localhost (or a VPN/bastion) and never expose it to the internet. If it
  must listen on another interface, scrape it through a reverse proxy with
  TLS and real authentication in front.
- **AMQP ports:** `5672` plain, `5671` for TLS (`amqps://`). For production
  prefer TLS: install a certificate on the broker and set
  `messaging.rabbitmq.uri: amqps://continuum-app:<password>@broker.internal:5671/continuum`
  in every node's `config.yml`.
- **Credentials in the URI.** The URI may embed user:password; treat that
  file accordingly (see [configuration tour](#step-5--configuration-tour)).
  Continuum never echoes secrets from the config into logs or error reports —
  violations are reported as paths and rules, never values.
- **Retries and the DLQ.** On publish/consume failure Continuum retries with
  the configured backoff (defaults: 5 attempts, first retry after 200ms,
  ceiling 10s) and broker-side delivery limits as a backstop; after that the
  message lands in the bounded quorum `continuum.events.dlq`, whose depth you
  should watch (`rabbitmqctl list_queues -p continuum`) or alert on. The dead
  letters are for inspection and replay — they do not silently vanish.
- **Exchange name.** Default `continuum.events`, changeable via
  `messaging.rabbitmq.exchange` in `config.yml` on every node (keep them
  identical; it must match the derived DLX everywhere).

### Degraded mode (`messaging.rabbitmq.enabled=false`)

With the broker disabled, Continuum makes **no** connection attempt and no
topology declaration whatsoever; events accumulate in the database outbox
until the broker is enabled again. Live proxy↔backend traffic over plugin
messaging (`continuum:main`) keeps working, so a small network runs fine —
you lose durable, restart-proof handoffs and releases, not the session flow.
Cross-node operations that require guaranteed delivery simply wait in the
outbox and are delivered when the relay resumes. It degrades explicitly and
visibly, never silently: which mode you are in is a config line, and the
outbox is a database table you can inspect with plain SQL.

Set `messaging.rabbitmq.enabled=false` on **every** node — enabling it on
only some nodes is the kind of inconsistency this system does not forgive
quietly. Note that if both transports are disabled (`messaging.rabbitmq.enabled=false`
*and* `messaging.plugin-message.enabled=false`), the config is rejected at
load time — Continuum refuses to run mute-deaf.

---

## Step 3 — Velocity (the coordinator)

One dedicated directory per proxy. Example based on a Linux host under
`/opt/velocity`; adapt to your layout.

### 3.1 Install Velocity

Download **Velocity 4.x** from the PaperMC site (papermc.io — Velocity lives
there alongside Paper and Folia) or build per its documentation, and put the
jar in `/opt/velocity/`. Create a start script:

```bash
java -Xms512M -Xmx1G -jar velocity-4.3.1.jar
```

Velocity generates its `velocity.toml` and (in modern forwarding mode)
`forwarding.secret` on first boot. Run it once, stop it, and then edit.

### 3.2 `velocity.toml`

The keys that matter, with safe production values. The proxy is the only
public-facing process in the topology:

```toml
# bind the public listener — this is the port players connect to
bind = "0.0.0.0:25565"

# modern forwarding, non-negotiable for a public network
online-mode = true
force-key-authentication = true
player-info-forwarding-mode = "modern"

# the auto-generated secret; contents are shared with every backend
forwarding-secret-file = "forwarding.secret"

[servers]
# names here must match what your backends and the [try] order expect;
# they are also the names plugins see on the wire
lobby-1 = "127.0.0.1:25566"
survival-1 = "127.0.0.1:25567"

# order Velocity tries on login and after a kick; first entry is the default
try = ["lobby-1"]

[forced-hosts]

(Trim or extend as fits your network — but keep the four forwarding keys
exactly as shown.)

- `online-mode = true` — players authenticate with Mojang; do not run a
  public network in offline mode.
- `force-key-authentication = true` — pins the session to the Mojang key
  pairs, closing the stolen-session-token class of attack on `online-mode`
  clients.
- `player-info-forwarding-mode = "modern"` — the modern forwarding handshake
  Velocity and Paper/Folia share. `legacy`/`bungeecord`-style forwarding and
  `none` are not supported configurations for Continuum.
- `forwarding-secret-file = "forwarding.secret"` — the file that holds the
  shared secret. Alternatively set the environment variable
  `VELOCITY_FORWARDING_SECRET` — it overrides the file, which is handy in
  container deployments where you inject secrets via the environment. If both
  are present, the environment variable wins.

**The secret is created on first run** (modern mode, missing file): Velocity
writes a fresh random secret into `forwarding.secret`. Keep it private
(`chmod 600 forwarding.secret`), never commit it, and paste its **contents** —
not the path — into every backend's `paper-global.yml` (next step).

You may also want `velocity.toml`'s `show-max-players`, `motd`, etc. —
unchanged from a vanilla Velocity setup; Continuum does not care.

### 3.3 Install the proxy jar

Create `/opt/velocity/plugins/` and drop in
`continuum-velocity-<version>.jar` (e.g. `continuum-velocity-build-<N>.jar`).

### 3.4 First boot of the proxy

Start Velocity. On first run the plugin:

1. seeds `plugins/continuum/config.yml` from the bundled reference
   (`ConfigLoader.seedIfMissing` — it never overwrites an existing file),
2. seeds `plugins/continuum/velocity.yml` likewise (proxy-local tunables:
   currently `velocity.reconnect-debounce-seconds`, default `10`),
3. loads and validates both; **on any validation failure the plugin fails to
   enable** and the log lists *every* violation found, with paths and rules —
   never secret values, one report, not fifty restarts.

Then edit `plugins/continuum/config.yml` — the one change you must make:

```yaml
network:
  id: main-network          # same on every node
  server-id: proxy-1        # unique per node
  role: coordinator          # <-- MUST; the bundled default is "backend"
```

Set the shared `database` block identically to every backend (Step 1), pick
`messaging.rabbitmq` (Step 2) or disable it, and you are done with the proxy
unless you want to tweak `sync.*` (see [configuration tour](#step-5--configuration-tour)).

Expected log lines on the proxy's healthy boot:

```
[Continuum] Continuum <version> ready: node=proxy-1 network=main-network role=coordinator
```

(The proxy keeps boot deliberately cheap — seeded config files, no database or broker work on the init path — so there is no "seeded…" line to wait for. Backends log their own seeding and validation lines, see Step 4.)

### 3.5 Permissions for the console commands

The proxy registers the `continuum` command tree at startup (before the first
connection is accepted):

| Command                    | Permission         | What it does                                   |
|----------------------------|--------------------|------------------------------------------------|
| `/continuum`               | everyone           | Shows the help document                        |
| `/continuum version`       | everyone           | Shows Continuum and Velocity versions          |
| `/continuum status`        | `continuum.admin`  | Node/network/role, backends + connection counts, plugin-message channel state and frame count |
| `/continuum reload`        | `continuum.admin`  | Re-reads and re-validates `config.yml`, `velocity.yml` and locales; applied atomically, previous graph kept on failure |
| `/continuum license redeem <code>` | `continuum.license` | Redeem a license code — see note below |
| `/continuum license check` | `continuum.license` | Show the state of the registered license      |
| `/continuum license reset` | `continuum.license` | Clear locally registered license state        |
| `/continuum update`        | `continuum.update` | Report update availability — see note below   |

Console has all permissions. For players, grant `continuum.admin` (and the
rest) through your permission plugin (LuckPerms or similar) — without one,
only the console can run them.

Two honest provisos for this build:

- The purchase-side license service is being built and is **not wired into
  current builds**; `/continuum license …` therefore reports "not configured".
  Self-builders never need it — AGPL means the jar you build works fully today.
  See [licensing](licensing.md) for the plan and what the $3.99 readiness build
  will include.
- `/continuum update` is registered but reports "unavailable" in current
  builds; the updater is part of the paid-build story. Nothing on your server
  is gated on it.

---

## Step 4 — Backends (Paper or Folia)

Repeat for every backend. Below, `/opt/survival-1` is one backend; your paths
will differ. On Folia the steps are identical — the same jar runs, and it
declares `folia-supported: true`.

### 4.1 `server.properties`

```properties
online-mode=false
server-ip=127.0.0.1
server-port=25566          # a different port per backend, matching velocity.toml
```

`online-mode=false` is mandatory: the proxy performed the authentication; the
backend must not do it again, or every player will fail to log in.

### 4.2 `spigot.yml`

```yaml
settings:
  bungeecord: false        # Continuum uses modern forwarding, not the legacy BungeeCord handshake
```

### 4.3 `config/paper-global.yml`

Modern forwarding, backend half. Paper reads this from the server's
`config/` directory (create it if absent):

```yaml
proxies:
  velocity:
    enabled: true
    online-mode: true        # MUST match velocity.toml's online-mode
    secret: "<paste the CONTENTS of forwarding.secret here>"
```

Two traps that account for almost every failed first boot:

1. **`secret` is the contents of the file, not the file name or path.**
   `secret: "forwarding.secret"` compiles fine and then rejects every login
   with an HMAC failure.
2. `online-mode` here must be **the same value** as in `velocity.toml`.
   Mismatched true/false is a failed handshake, every time, for everyone.

Keep the secret file and this file out of version control. If it leaks,
rotate it: new file on the proxy, new `paper-global.yml` on every backend,
restart all of them.

### 4.4 Bind and firewall

`server-ip=127.0.0.1` assumes the proxy runs on the same host. If your
backends live on other machines, bind a **private** address (e.g.
`10.0.0.11`) instead of `0.0.0.0`, and firewall the backend ports so only the
proxy can reach them:

```bash
# ufw example — only the proxy host may reach the backend ports
ufw deny 25566:25570/tcp
ufw allow from 10.0.0.5 to any port 25566:25570 proto tcp   # the proxy host
```

Modern forwarding authenticates the handshake; it is **not** a firewall. A
backend port open to the world is a backend port anyone who knows a secret —
or a legacy-fallback client — can knock on. Bind private, firewall always.

### 4.5 Install the backend jar

Create `/opt/survival-1/plugins/` and drop in
`continuum-paper-<version>.jar` (same jar for Folia).

### 4.6 First boot of a backend

Start the server once. The plugin:

1. seeds `plugins/Continuum/config.yml` (shared network config — edit it like
   the proxy's) and `plugins/Continuum/platform.yml` (backend-local tunables:
   the join gate, failure limits, restore timeout, spawn policy),
2. validates both; failure aborts the load and the log lists every violation,
3. arms the join gate,
4. logs `configuration valid: network '...', this node '...', default locale en_US, version ...`.

Then set, in `plugins/Continuum/config.yml`:

```yaml
network:
  id: main-network          # same on every node
  server-id: survival-1     # unique per backend
  role: backend
```

And mirror the shared `database` (Step 1) and `messaging` (Step 2) blocks from
the proxy **exactly** — same host, user, schema, exchange, channel.

`platform.yml` ships sane defaults; you only need to touch it if you care
about the gate:

```yaml
gate:
  enabled: true
  max-failures: 3               # consecutive restore failures before refusing this player
  failure-cooldown-minutes: 10
  restore-timeout-seconds: 5    # players are kicked if a restore wedges; 0 disables (dangerous)
  disallow-on-storage-degraded: true   # fail-closed: storage down blocks joins

spawn-policy:
  mode: SERVER_SPAWN            # SERVER_SPAWN | LAST_KNOWN_LOCATION | DISABLED
  world: ""                     # empty = default world
```

If storage cannot serve snapshots, `disallow-on-storage-degraded: true` does
what it says: players are refused rather than dropped onto half-restored
state. Leave it on unless you are deliberately testing without storage.

---

## Step 5 — Configuration tour

All nodes share `config.yml` in their plugin data directories — the Velocity
proxy keeps `plugins/continuum/config.yml`, backends keep `plugins/Continuum/config.yml`.
The bundled reference is the file we quote below; when in doubt, diff against
it. Missing keys fall back to defaults; wrong values are **hard errors with a
full violation report**, never silent guesses.

```yaml
debug: false            # master debug flag; keep false in production

network:
  id: main-network        # [a-z0-9][a-z0-9._-]*, max 63 chars; the whole network shares it
  server-id: survival-1   # this node's unique id; same charset
  role: backend           # backend | coordinator (Velocity MUST be coordinator)

database:
  type: mysql             # mysql | mariadb | mongodb
  mysql:                  # mariadb shares this block; only the driver differs
    host: localhost
    port: 3306
    database: continuum
    username: continuum
    password: ""          # never commit a real password
    pool:
      maximum-pool-size: 10
      minimum-idle: 2     # must be <= maximum-pool-size or the config is rejected
      connection-timeout: 5s
      max-lifetime: 30m
  mongodb:
    uri: ""               # mongodb:// or mongodb+srv://; required when type=mongodb
    database: continuum
    pool:
      max-size: 50
      min-size: 2
      max-wait-time: 5s

messaging:
  rabbitmq:
    enabled: true          # false = degraded plugin-messaging-only mode; events queue in the outbox
    uri: "amqp://localhost:5672/"   # amqp:// or amqps://; may embed credentials
    exchange: continuum.events      # durable; a DLX is derived from it
    retry:
      max-attempts: 5
      initial-backoff: 200ms
      max-backoff: 10s
  plugin-message:
    enabled: true
    channel: continuum:main          # wire constant — leave at the default
    max-payload-bytes: 65536         # 1024-65536; larger frames are dropped before decode

sync:
  autosave:
    enabled: true
    interval: 60s            # 5s-24h
  modules:                   # set a known module to false to stop syncing it
    inventory: true
    ender-chest: true
    vitals: true
    experience: true
    effects: true
    attributes: true
    location: true
    gamemode: true
    advancements: true
    statistics: true
    recipes: true
    pdc: true
    custom: true             # unknown ids are rejected at load
  transfer:
    require-snapshot: true   # never transfer without a durable pre-transfer snapshot
    restore-before-play: true
  recovery:
    enabled: true
    interval: 30s
    restore-order: []        # empty = default order; ids must be known modules, unique

i18n:
  default-locale: en_US
  fallback-locale: en_US
  use-client-locale: true

serialization:
  gzip: false                # measure on your workload before enabling
  max-snapshot-bytes: 8388608  # 8 MiB; 65536-67108864
```

Pointers worth your attention:

- **`sync.modules`** is a closed world. An unknown id — usually a typo — is
  rejected at load with the full list of known modules, instead of silently
  syncing nothing for one server. Turn modules off to slim deployments
  (e.g. no `recipes` on a minigames backend).
- **`sync.transfer.require-snapshot` / `restore-before-play`** exist so that a
  player never starts playing on half-restored state and a transfer never
  begins without a durable snapshot on disk. Keep both true.
- **`messaging.plugin-message.channel`** is a wire constant (`continuum:main`)
  on the proxy side and a config knob on the backend side. They must agree.
  The simplest way to guarantee that is to never change it.
- **`messaging.plugin-message.max-payload-bytes`** caps frame size; anything
  larger is dropped before decode. Raising it past 65536 is rejected at load.
- **Secrets in this file are plain strings** by design; *how* you protect the
  file (permissions, templating, vault injection) is your deployment's
  business. `chmod 600` on every config, the forwarding secret and the DB
  passwords, and never `git add` any of them.
- **`/continuum reload`** (proxy) re-reads `config.yml`, `velocity.yml` and
  locales and swaps them in one shot; if validation fails, the **previous
  configuration stays active** and the error is logged. Backends reload by
  restart in this build.

---

## First-start checklist

Go in order; each step's failure tells you where to look. "Current build"
refers to what is wired in the development tree at the time of writing.

1. **Database created**, user grants applied (Step 1), reachable from every
   node: `mysql -h <host> -u continuum -p continuum -e 'SELECT 1'` (or
   `mongosh "<srv-uri>" --eval 'db.runCommand({ping:1})'`).
2. **RabbitMQ ready** (or deliberately disabled): vhost `continuum` exists,
   `continuum-app` can authenticate and has `continuum.*`-scoped permissions,
   management UI not publicly reachable (Step 2).
3. **Velocity configured** (Step 3): `velocity.toml` has the four forwarding
   keys; `forwarding.secret` generated on first run; `plugins/continuum/config.yml`
   has `network.role: coordinator`, a unique `network.server-id`, and the same
   `network.id` as the backends; jar in `plugins/`.
4. **Backends configured** (Step 4): `online-mode=false`,
   `server-ip=127.0.0.1` or a private address, `bungeecord=false`,
   `paper-global.yml` `velocity.secret` = **contents** of `forwarding.secret`,
   `velocity.online-mode` matches `velocity.toml`; jars in `plugins/`;
   `plugins/Continuum/config.yml` has `network.role: backend`, a unique
   `network.server-id`.
5. **Start order: database → broker → backends → proxy.** Watching the
   backend logs first surfaces config errors before any player can connect.
   Expected lines:
   - backend: `configuration valid: network 'main-network', this node 'survival-1', default locale en_US, version ...`
   - proxy: `Continuum <version> ready: node=proxy-1 network=main-network role=coordinator`
6. **First-boot seeding** is normal: on the very first start each backend
   writes its default config and logs
   `seeded the default configuration at ... — review it before production use`
   (and the same for `platform.yml`). The proxy seeds `config.yml` and
   `velocity.yml` silently and logs only its ready line. Review the seeded
   files before you expose anything.
7. **`/continuum status` on the proxy** (`continuum.admin`; console always).
   It reports the node/network/role, every backend Velocity knows with its
   connection count, player counts (online/awaiting), the plugin-message
   channel state (`continuum:main`), frames accepted, and the time of the
   last frame. If the channel shows anything other than enabled with frames
   arriving after a player joins, something upstream is wrong.
8. **Join through the proxy** with a test account: you should land on your
   default backend. Watch the backend log. On the **current development
   build the synchronization layer is not wired into the plugins yet** (the
   join gate arms and then lifts immediately; the storage and messaging
   engines land separately), so do **not** expect inventory to follow you
   between servers yet — that is the next release. What you *can* verify
   today: the proxy lists your backends, connections route, `/continuum status`
   counts them, and config validation errors behave as documented.
9. **Violations?** A broken `config.yml` aborts the plugin's load with a
   single report listing every problem found. Fix *all* of them — fix one at
   a time and the log will just repeat with the remaining ones.
10. **Back up immediately once healthy.** A fresh production deployment is a
    one-line `mysqldump`/`mongodump` away from a clean state; take one before
    real players arrive (see [Upgrading](#upgrading)).

---

## Misconfigurations and how they fail

| Symptom | Cause | Fix |
|---|---|---|
| Players are disconnected immediately after the proxy handshake / HMAC verification error in logs | `paper-global.yml` `secret` mismatch: wrong contents, the file *path* pasted instead of the contents, or stale copy after rotation | Paste the exact contents of the proxy's `forwarding.secret` into every backend; restart backends |
| Same HMAC failure for **all** players | `online-mode` differs between `velocity.toml` and `paper-global.yml` (true vs false) | Make them identical |
| Backend is reachable directly, bypassing the proxy (players connect on 25566, no proxy in front) | `server-ip=0.0.0.0` / no firewall | Bind `127.0.0.1` or a private address; firewall backend ports to the proxy only. **Modern forwarding is not a firewall.** |
| `Public Key Retrieval is not allowed` on MySQL 8.4 | `caching_sha2_password` over a plaintext connection | Enable TLS on the server (`require_secure_transport=ON`). Keep `caching_sha2_password` — do **not** switch to `mysql_native_password`. Do not "fix" with `allowPublicKeyRetrieval` on anything but a trusted private link |
| `Access denied for user 'continuum'@...` | Wrong user/host pattern (`continuum` vs `continuum@%` vs `continuum@10.0.%`), wrong password, or a forgotten `FLUSH PRIVILEGES` | Align the `CREATE USER` host pattern with where the nodes actually connect from |
| HikariCP: `Connection is not available, request timed out after 5000ms` | Database slow/unreachable, or pool too small for the network size | Raise `connection-timeout` (≤60s) and/or `maximum-pool-size`; check DB `max_connections`; keep `minimum-idle <= maximum-pool-size` or the config is rejected at load |
| MongoDB: connection/auth failures right after provisioning | Atlas Network Access allowlist empty; SRV string with wrong `authSource`; user created on the wrong database | Add node IPs to the Atlas allowlist; create the user with `readWrite` on `continuum`; use `mongodb+srv://.../continuum` |
| Plugin fails to enable at startup; log lists dozens of violations | Any invalid value in `config.yml` (unknown module id, bad locale, out-of-range pool) | Read the one report; it lists **every** violation — fix them all, one restart (see [checklist](#first-start-checklist)) |
| Startup refused: *"messaging: both transports are disabled; enable at least one of messaging.rabbitmq.enabled or messaging.plugin-message.enabled"* | `rabbitmq.enabled=false` **and** `plugin-message.enabled=false` | Enable at least one |
| Node starts with no RabbitMQ connectivity even though it was supposed to | `messaging.rabbitmq.enabled=true` while the broker is down — a promised broker that cannot be reached fails fast, on purpose | Bring the broker up (or set `enabled=false` everywhere and accept the outbox deferral). Live plugin messaging resumes with the broker; durable events drain from the outbox |
| Events pile up; `continuum.events.dlq` grows | Consumer rejects poisoned messages, or broker was down past the retry budget | Inspect DLQ (`rabbitmqctl list_queues -p continuum`), fix the cause, replay if appropriate — Continuum will not discard them silently |
| Proxy behaves like a backend (joins, no routing) | `network.role: backend` on the Velocity node | `role: coordinator` in the proxy's `config.yml` (bundled comment warns about exactly this) |
| Two backends fighting over sessions / one steals the other's players | Duplicate `network.server-id` | Give each node a unique `server-id`; validation checks the charset only, not uniqueness |
| Player state "teleports" to an older snapshot after a crash | A stale server won a race it should not have | Fencing tokens + `sync.transfer.require-snapshot: true` exist for this; if it happens, gather logs and report it — it is a bug, not a tuning problem |
| Config works but half the network is on an old jar | Per-node jar drift | Same jar version on every node; see [Upgrading](#upgrading) |

---

## Upgrading

**Always back up first.** Two commands cover the state:

```bash
# SQL
mysqldump --single-transaction --set-gtid-purged=OFF -h <host> -u continuum -p continuum > continuum-$(date +%F).sql
# or, on MariaDB: mariadb-dump --single-transaction ...

# MongoDB
mongodump --uri="mongodb://continuum:<password>@db.internal:27017" --db=continuum --out=continuum-$(date +%F)/
```

Also back up `config.yml`/`platform.yml`/`velocity.yml` and
`forwarding.secret` from each node — they are small and irreplaceable.

**Jar drop-in.** Continuum ships as two shaded jars; an upgrade is replacing
them. Keep every node on the **same build** — while a rolling restart is in
flight, a few moments of mixed builds behind the proxy is expected and fine,
but do not run the network permanently skewed.

**Rolling restart behind the proxy: backends first, one at a time, Velocity
last.** While a backend restarts, players on it are kicked and (with a
working setup) Velocity routes them upon reconnect; the proxy being up the
whole time keeps the frontend stable throughout.

Schema migrations run automatically at storage start. A newer jar with newer
`VNNN__name.sql` migrations applies them on first boot; if applied-migration
checksums do not match this build, startup aborts rather than risk a
half-migrated schema — restore from your backup and file a report. **Keep the
whole network on one Minecraft version at a time**; snapshot migration is
forward-only, never sideways.

Rollback: stop proxy and backends, restore the database backup, drop the new
jars in with the old ones, start backends then the proxy, verify with
`/continuum status` and a test join. Because snapshots migrate forward only,
never point an older jar at data written by a newer one.

---

## Uninstalling

1. Stop the proxy and every backend (Velocity last of the running ones,
   backends first).
2. Remove the jars (`continuum-velocity-*.jar`, `continuum-paper-*.jar`) and
   the plugin data directories: `plugins/continuum/` on the proxy,
   `plugins/Continuum/` on every backend (they contain `config.yml`,
   `velocity.yml`/`platform.yml`, locales and caches — all regenerable, none
   needed afterwards).
3. Drop the database objects: `DROP DATABASE continuum;` and
   `DROP USER 'continuum'@...;` (SQL), or `use continuum; db.dropDatabase();`
   plus removing the user in `mongosh` (MongoDB).
4. RabbitMQ: `rabbitmqctl delete_vhost continuum` — the exchanges and queues
   live inside the vhost and are removed with it. The application user can go
   too (`rabbitmqctl delete_user continuum-app`).
5. Delete `forwarding.secret`, revert `server.properties`/`spigot.yml`/
   `paper-global.yml` to your previous values, and remove any firewall rules
   you added.
6. Prune DNS/inventory entries (`survival-1` etc.) if you added them.

Nothing else in the server directories is touched by Continuum.

---

## Links

- Project overview, build-from-source instructions and the license: [README](../README.md)
- Licensing, what the ready-made build includes, and how purchasing will work: [licensing](licensing.md)
- Release process and how versions are built (`build-<N>`): [release runbook](release.md)
- Compatibility matrix: ships with the next release (docs/compatibility.md does not exist yet — versions above are what we test and pin)
- Security policy (report vulnerabilities privately, never as public issues): [SECURITY](../.github/SECURITY.md)
- License text: [LICENSE](../LICENSE) (AGPL-3.0-or-later)
- Contributing and code of conduct: [CONTRIBUTING](../CONTRIBUTING.md)
- Bug reports and feature requests: [issue templates](../.github/ISSUE_TEMPLATE/) — include versions, platform and logs (redact secrets)
