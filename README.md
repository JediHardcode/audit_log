# audit-log-service

Immutable append-only audit log service. Accepts events from internal services and stores them with tamper-evidence via hash chain.

## Local setup

```bash
cp .env.example .env
# Fill in .env values
docker compose up -d
./gradlew bootRun
```

> **Testcontainers on Docker Desktop**: add `testcontainers.reuse.enable=true` to `~/.testcontainers.properties` to speed up repeated test runs.

## Run tests

```bash
./gradlew test
```

Integration tests use Testcontainers and require Docker. On GitHub Actions (`ubuntu-latest`) Docker is available out of the box.

## API examples

### POST /audit-events

```bash
curl -X POST http://localhost:8080/audit-events \
  -H 'Content-Type: application/json' \
  -d '{
    "actor": "user:42",
    "action": "project.updated",
    "resource": "project:17",
    "outcome": "success",
    "context": {"ip": "1.2.3.4"}
  }'
```

### GET /audit-events

```bash
# All events, first page
curl 'http://localhost:8080/audit-events'

# Filter by actor, time range, page 2
curl 'http://localhost:8080/audit-events?actor=user:42&from=2026-01-01T00:00:00Z&page=1&size=20'
```

## Invariants

- `timestamp` is always set by the server — any client-supplied value is ignored
- Events are append-only — no updates or deletes (except scheduled archival via RetentionJob)
- Hash chain links every event to its predecessor for tamper detection
