CREATE TABLE audit_events (
    id           UUID        PRIMARY KEY,
    timestamp    TIMESTAMPTZ NOT NULL,
    actor        TEXT        NOT NULL,
    action       TEXT        NOT NULL,
    resource     TEXT        NOT NULL,
    outcome      TEXT        NOT NULL,
    context      JSONB,
    prev_hash    TEXT,
    event_hash   TEXT        NOT NULL
);

CREATE INDEX idx_audit_events_actor     ON audit_events (actor);
CREATE INDEX idx_audit_events_resource  ON audit_events (resource);
CREATE INDEX idx_audit_events_timestamp ON audit_events (timestamp DESC);
