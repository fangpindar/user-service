CREATE TABLE users (
    id              BIGSERIAL    PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(60)  NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING_ACTIVATION',
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT users_status_check CHECK (
        status IN ('PENDING_ACTIVATION', 'ACTIVE', 'LOCKED', 'DEACTIVATED')
    )
);
