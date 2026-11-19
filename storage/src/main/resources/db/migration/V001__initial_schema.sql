-- Continuum persistence schema, v1.
-- utf8mb4 / utf8mb4_unicode_ci everywhere, InnoDB, DATETIME(6) in UTC, BIGINT tokens.
-- All DDL is idempotent (CREATE TABLE IF NOT EXISTS): MySQL/MariaDB commit DDL
-- implicitly, so a half-applied migration is re-run at next boot; keep it that way.
-- Time columns hold UTC wall time; the storage layer always converts via ZoneOffset.UTC.

CREATE TABLE IF NOT EXISTS players (
    player_uuid CHAR(36)     NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (player_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One row per player: the CURRENT snapshot.
-- fencing_token is the ONLY thing that authorizes a write: the guarded
-- UPDATE matches on fencing_token < ?, never on anything else.
CREATE TABLE IF NOT EXISTS player_snapshot (
    player_uuid    CHAR(36)     NOT NULL,
    schema_version INT          NOT NULL,
    payload        MEDIUMBLOB   NOT NULL,
    fencing_token  BIGINT       NOT NULL,
    saved_at       DATETIME(6)  NOT NULL,
    source_backend VARCHAR(64)  NOT NULL,
    reason         VARCHAR(32)  NOT NULL,
    modules_json   TEXT         NOT NULL,
    PRIMARY KEY (player_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Append-only history. Rollback = insert a copy of an old row under a fresh
-- token; rows are never updated, only pruned to the configured keep.
CREATE TABLE IF NOT EXISTS player_snapshot_history (
    snapshot_id    BIGINT       NOT NULL AUTO_INCREMENT,
    player_uuid    CHAR(36)     NOT NULL,
    schema_version INT          NOT NULL,
    payload        MEDIUMBLOB   NOT NULL,
    fencing_token  BIGINT       NOT NULL,
    saved_at       DATETIME(6)  NOT NULL,
    source_backend VARCHAR(64)  NOT NULL,
    reason         VARCHAR(32)  NOT NULL,
    modules_json   TEXT         NOT NULL,
    PRIMARY KEY (snapshot_id),
    KEY idx_player_snapshot_history_player (player_uuid, snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Accumulate-mutations only: stat_value = stat_value + delta via ODKU.
-- BIGINT counters; fractional stats are intentionally out of scope here.
CREATE TABLE IF NOT EXISTS player_stats (
    player_uuid CHAR(36)    NOT NULL,
    stat_key    VARCHAR(64) NOT NULL,
    stat_value  BIGINT      NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (player_uuid, stat_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sessions (
    session_id        BIGINT       NOT NULL AUTO_INCREMENT,
    player_uuid       CHAR(36)     NOT NULL,
    server_id         VARCHAR(64)  NOT NULL,
    token             BIGINT       NOT NULL,
    state             VARCHAR(32)  NOT NULL,
    started_at        DATETIME(6)  NOT NULL,
    last_heartbeat    DATETIME(6)  NOT NULL,
    lease_expires_at  DATETIME(6)  NOT NULL,
    ended_at          DATETIME(6)  NULL,
    close_reason      VARCHAR(16)  NULL,
    PRIMARY KEY (session_id),
    UNIQUE KEY uk_sessions_token (token),
    KEY idx_sessions_player_token (player_uuid, token),
    KEY idx_sessions_lease (lease_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Transactional outbox. Poll with published_at IS NULL; claim with
-- FOR UPDATE SKIP LOCKED so concurrent publishers never double-claim.
CREATE TABLE IF NOT EXISTS outbox (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    msg_type      VARCHAR(64)  NOT NULL,
    player_uuid   CHAR(36)     NOT NULL,
    payload_json  MEDIUMTEXT   NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    published_at  DATETIME(6)  NULL,
    attempts      INT          NOT NULL DEFAULT 0,
    last_error    VARCHAR(512) NULL,
    PRIMARY KEY (id),
    KEY idx_outbox_poll (published_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Single global fencing token source: one row, monotonically incremented
-- inside the acquiring transaction (UPDATE ... LAST_INSERT_ID(val+1)).
CREATE TABLE IF NOT EXISTS fencing_counter (
    id  TINYINT NOT NULL,
    val BIGINT  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed the counter. ODKU no-op keeps this idempotent when a half-applied
-- migration is re-run at next boot.
INSERT INTO fencing_counter (id, val)
VALUES (1, 0)
ON DUPLICATE KEY UPDATE val = val;

-- Migration bookkeeping. Managed by the migration runner; never write here by hand.
CREATE TABLE IF NOT EXISTS schema_migrations (
    version      BIGINT       NOT NULL,
    name         VARCHAR(255) NOT NULL,
    checksum     CHAR(64)     NOT NULL,
    execution_ms BIGINT       NOT NULL,
    applied_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
