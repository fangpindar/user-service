CREATE TABLE login_audit (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    login_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address  VARCHAR(45),
    user_agent  VARCHAR(500)
);
CREATE INDEX idx_login_audit_user_login_at ON login_audit(user_id, login_at DESC);
