-- Composite indexes for GET /audit-events (cursor pagination + filters).
-- See .specs/query-api/design.md#indexes.
--
-- Single-column actor/resource/timestamp indexes are strict subsets of the
-- composites below — Postgres uses the leftmost prefix of a composite, so
-- the old indexes are redundant. Drop them to save write cost and disk space.
--
-- Schema-only change. No data is mutated; append-only invariant preserved.

DROP INDEX IF EXISTS idx_audit_events_actor;
DROP INDEX IF EXISTS idx_audit_events_resource;
DROP INDEX IF EXISTS idx_audit_events_timestamp;

CREATE INDEX idx_audit_events_ts_id
    ON audit_events (timestamp DESC, id DESC);

CREATE INDEX idx_audit_events_actor_ts_id
    ON audit_events (actor, timestamp DESC, id DESC);

CREATE INDEX idx_audit_events_resource_ts_id
    ON audit_events (resource text_pattern_ops, timestamp DESC, id DESC);

CREATE INDEX idx_audit_events_outcome_ts_id
    ON audit_events (outcome, timestamp DESC, id DESC);
