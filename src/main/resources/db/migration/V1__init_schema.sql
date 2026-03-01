-- ════════════════════════════════════════════════════
-- GostForge — V1: Initial schema (Quick Convert, no projects)
-- ════════════════════════════════════════════════════

CREATE TABLE users (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    username         VARCHAR(50)  NOT NULL UNIQUE,
    email            VARCHAR(255) NOT NULL UNIQUE,
    password_hash    VARCHAR(255) NOT NULL,
    display_name     VARCHAR(100),
    telegram_chat_id BIGINT       UNIQUE,
    storage_quota_mb INT          NOT NULL DEFAULT 100,
    file_ttl_days    INT          NOT NULL DEFAULT 30,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE conversion_jobs (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID         NOT NULL REFERENCES users(id),
    status         VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    output_format  VARCHAR(10)  NOT NULL DEFAULT 'DOCX',
    merged_md_key  VARCHAR(500),
    docx_key       VARCHAR(500),
    pdf_key        VARCHAR(500),
    md2gost_job_id VARCHAR(100),
    error_message  TEXT,
    error_stage    VARCHAR(50),
    started_at     TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_jobs_user   ON conversion_jobs(user_id);
CREATE INDEX idx_jobs_status ON conversion_jobs(status);

CREATE TABLE personal_access_tokens (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    scopes      VARCHAR(200) NOT NULL DEFAULT 'api:full',
    last_used   TIMESTAMPTZ,
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_pat_user ON personal_access_tokens(user_id);
CREATE INDEX idx_pat_hash ON personal_access_tokens(token_hash);

CREATE TABLE user_cas_files (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sha256           VARCHAR(64)  NOT NULL,
    size_bytes       BIGINT       NOT NULL,
    last_accessed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (user_id, sha256)
);

CREATE INDEX idx_cas_user     ON user_cas_files(user_id);
CREATE INDEX idx_cas_user_lru ON user_cas_files(user_id, last_accessed_at);
