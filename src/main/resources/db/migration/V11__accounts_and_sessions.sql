-- Independent installation identity; sports transfer never touches these tables.
CREATE TABLE auth_installation (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    installation_id UUID NOT NULL UNIQUE
);
INSERT INTO auth_installation(singleton, installation_id) VALUES (1, gen_random_uuid());

CREATE TABLE auth_accounts (
    id UUID PRIMARY KEY,
    login VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(480) NOT NULL,
    password_hash VARCHAR(512) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('ORGANIZER', 'ADMIN')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'BLOCKED')),
    must_change_password BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (login = lower(login))
);
CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES auth_accounts(id),
    credential_digest VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reauthenticated_until TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX auth_sessions_account_idx ON auth_sessions(account_id);
CREATE INDEX auth_sessions_activity_idx ON auth_sessions(last_activity_at);

CREATE TABLE auth_audit (
    id UUID PRIMARY KEY,
    actor_id UUID,
    target_id UUID,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    action VARCHAR(48) NOT NULL,
    result VARCHAR(48) NOT NULL
);
CREATE INDEX auth_audit_time_idx ON auth_audit(occurred_at);
