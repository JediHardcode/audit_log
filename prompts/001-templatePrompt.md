Прочитай AGENTS.md — там описаны инварианты, модель событий и ограничения проекта. Всё что ты генерируешь должно им соответствовать.

## Стек и общие требования

- Java 21, Spring Boot 3.x, Gradle Groovy DSL
- PostgreSQL + Flyway
- Testcontainers для интеграционных тестов
- Lombok нужен 
- Jackson для JSON, Hibernate Validator для `@Valid`

## Структура проекта

```
src/main/java/com/example/auditlog/
  api/              — контроллеры, DTO, обработка ошибок
  domain/           — доменные сущности, enum Outcome, сервисы
  persistence/      — JPA entity, Spring Data репозитории
  hashchain/        — расчёт hash chain
  retention/        — scheduled job для архивации
  config/           — конфигурация Spring, OpenAPI, Jackson
  AuditLogApplication.java
src/main/resources/
  application.yml
  db/migration/V1__init.sql
src/test/java/...
  unit/             — юнит-тесты сервисов (mock репозитория)
  integration/      — интеграционные тесты со Spring context + Testcontainers
```

Слои: `Controller → Service → Repository`. Контроллер не лезет в репозиторий напрямую.

## API контракты

### POST /audit-events

Request:
```json
{
  "actor": "user:42",
  "action": "project.updated",
  "resource": "project:17",
  "outcome": "success",
  "context": { "any": "json" }
}
```

- `actor`, `action`, `resource` — `@NotBlank`
- `outcome` — enum `success | denied | error`, `@NotNull`
- `context` — опциональный JSON-объект произвольного размера (без искусственного лимита на уровне приложения; положиться на ограничения PostgreSQL JSONB)
- `timestamp` из тела **игнорируется**, даже если прислан. Сервер выставляет `Instant.now()`.

Response `201 Created`:
```json
{
  "id": "uuid",
  "timestamp": "2026-04-23T10:15:30Z",
  "actor": "...",
  "action": "...",
  "resource": "...",
  "outcome": "success",
  "context": { ... },
  "prevHash": "hex",
  "eventHash": "hex"
}
```

### GET /audit-events

Query-параметры (все опциональные, комбинируются через AND):
- `actor` — точное совпадение
- `resource` — точное совпадение
- `from`, `to` — ISO-8601 Instant, диапазон по `timestamp` (включительно)
- `page` — номер страницы, по умолчанию `0`
- `size` — размер страницы, по умолчанию `50`, максимум `500`

Response `200 OK`:
```json
{
  "content": [ /* события */ ],
  "page": 0,
  "size": 50,
  "totalElements": 1234
}
```

Сортировка по `timestamp DESC`.

### Обработка ошибок

Единый `@RestControllerAdvice`. Формат:
```json
{ "error": "validation_failed", "message": "actor must not be blank", "details": [...] }
```
Коды:
- `400` — валидация / битый JSON / неверный enum
- `404` — не используется (событий не удаляем, но если запрос по id которого нет — `404`)
- `500` — всё остальное

## Миграции Flyway

`V1__init.sql` создаёт таблицу:

```sql
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
```

Миграции append-only: никакие последующие `Vx__*.sql` не должны содержать `UPDATE` или `DELETE` над `audit_events` (кроме retention job, который выполняется из кода и физически удаляет только после архивации — см. Retention).

## Persistence

- JPA entity `AuditEventEntity`, immutable после `persist` (сеттеров не генерировать, только конструктор/builder)
- `AuditEventRepository extends Repository<AuditEventEntity, UUID>` — **только** методы `save`, `findById`, `findLatest()` (для hash chain), и query-метод для поиска с фильтрами + пагинацией. Никаких `deleteXxx`, `updateXxx`.
- Поиск — через Spring Data Specifications либо `@Query` с динамическими условиями.

## Hash chain (tamper-evidence)

- При сохранении события:
  1. Взять `event_hash` последнего события (по `timestamp DESC, id`) → `prevHash`. Если таблица пуста → `prevHash = null` (или строка `"GENESIS"`).
  2. `eventHash = SHA-256(prevHash || id || timestamp || actor || action || resource || outcome || contextJsonCanonical)`. Hex-кодировка.
  3. Сохранить оба поля.
- Канонизация JSON для `context`: отсортированные ключи, без пробелов (использовать Jackson с `MapperFeature.SORT_PROPERTIES_ALPHABETICALLY` + `ORDER_MAP_ENTRIES_BY_KEYS`).
- Операция сохранения должна быть сериализована, чтобы hash chain не ломался при конкурентных вставках. Реализовать через `SERIALIZABLE` транзакцию либо advisory lock PostgreSQL (`pg_advisory_xact_lock`).
- Отдельный сервис `HashChainService` с методом `verifyChain()` — проходит по всем событиям и проверяет целостность. Покрыть тестом.

## Retention

- Конфиг: `audit.retention.days` в `application.yml`, по умолчанию `365`.
- Scheduled job (`@Scheduled`, cron ежедневно в 03:00) — события старше N дней:
  1. Сериализовать в архивный формат (JSON lines) и положить в директорию `audit.retention.archive-dir` (конфигурируемая).
  2. **Только после успешной записи архива** — удалить из таблицы.
- Удаление в retention — **единственное** место где допустим `DELETE`. Чётко изолировать в отдельном репозитории/классе `RetentionRepository` с методом `deleteOlderThan(Instant)`, задокументировать почему инвариант не нарушен.
- Hash chain при retention: архивированные события остаются в архиве, в БД chain продолжается от последнего оставшегося события. В комментарии к коду отметить, что верификация chain после retention работает только по живым данным + архиву вместе.

## Тесты

### Unit
- `AuditEventService` с mock репозитория — проверяет: server-side timestamp, игнор клиентского timestamp, валидацию outcome, вызов hash chain.
- `HashChainService.verifyChain()` — на фейковых данных.

### Integration (Testcontainers)
- `@SpringBootTest` + `PostgreSQLContainer` (`@Testcontainers`, `@DynamicPropertySource` подставляет URL).
- Тест подъёма контекста (smoke).
- POST + GET happy path.
- POST с `timestamp` в теле → сервер его игнорирует.
- POST без `actor` → `400`.
- Пагинация: вставить 120, запросить `size=50&page=1` → 50 элементов.
- Hash chain: вставить 3 события → `verifyChain()` проходит.

### Конфигурация Testcontainers
- `testcontainers.reuse.enable=true` в `~/.testcontainers.properties` — упомянуть в README.
- На GitHub Actions Docker уже есть в `ubuntu-latest`, дополнительных настроек не требуется.

## Build (`build.gradle`)

Зависимости:
- `spring-boot-starter-web`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-validation`
- `org.postgresql:postgresql`
- `org.flywaydb:flyway-core`, `flyway-database-postgresql`
- `org.projectlombok:lombok` (compileOnly + annotationProcessor)
- Testcontainers BOM, `testcontainers`, `junit-jupiter`, `postgresql` (testcontainers module)
- `spring-boot-starter-test`

Toolchain Java 21. Тесты запускаются через `./gradlew test`, интеграционные — тем же `test` таском (без разделения на source sets для простоты).

## Docker Compose

`docker-compose.yml` в корне — только Postgres для локальной разработки:
```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    ports:
      - "${DB_PORT}:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
volumes:
  pgdata:
```

## Environment

`.env.example` — шаблон с незаполненными значениями, коммитится в репозиторий:
```
POSTGRES_DB=
POSTGRES_USER=
POSTGRES_PASSWORD=
DB_PORT=5432
```

`.env` — реальные значения, **не коммитится** (добавить в `.gitignore`):
```
POSTGRES_DB=audit_log
POSTGRES_USER=audit
POSTGRES_PASSWORD=audit
DB_PORT=5432
```

`application.yml` читает значения через `${POSTGRES_DB}` и т.д. Spring Boot подхватит из env автоматически.

## Git

- `.gitignore`: `build/`, `.gradle/`, `.idea/`, `*.iml`, `out/`, `.env`, `*.log`, `.DS_Store`
- GitHub Actions workflow `.github/workflows/ci.yml`:
  - Trigger: `pull_request` на `main` + `push` на `main`
  - Steps: checkout → setup-java 21 (temurin) → `./gradlew build test`
  - Testcontainers работает на `ubuntu-latest` из коробки

## README

- Как запустить локально: `cp .env.example .env` → заполнить → `docker compose up -d` → `./gradlew bootRun`
- Как прогнать тесты: `./gradlew test`
- Замечание про Docker Desktop + Testcontainers на локале (reuse флаг)
- Примеры curl-запросов для POST и GET

## Verification

- `./gradlew build` проходит без ошибок и варнингов
- Smoke-тест на Testcontainers: Spring context поднимается
- Happy path POST + GET возвращает сохранённое событие
- `verifyChain()` возвращает true после серии вставок

## Чего не делать

- Не добавлять UPDATE в репозиторий
- Не добавлять DELETE нигде, кроме изолированного `RetentionRepository`
- Не принимать `timestamp` от клиента — игнорировать даже если прислали
- Не мокать базу в интеграционных тестах — только Testcontainers
- Не добавлять soft delete (`deleted_at`, `is_deleted`)
- Не генерировать файлы, которых нет в этом списке
- Не дублировать код, не придумывать поля которых нет в модели

## Нюансы

- Локальная разработка на Docker Desktop — могут быть нюансы с Testcontainers на локале, но на GitHub Actions runners тесты должны проходить штатно
- Если что-то непонятно — задать вопросы до генерации
- Если после пары попыток проблема не решается — написать в чат, а не костылить
