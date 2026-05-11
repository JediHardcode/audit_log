# audit-log-service

## Project map

Внутренний сервис компании: принимает аудит-события от других сервисов и хранит их immutable.
Нужен для compliance, security, observability. Читатели: compliance-офицеры, SRE, security-аналитики.

## API

```
POST /audit-events            — принять одно событие
GET  /audit-events            — поиск по actor / resource / time range / outcome,
                                сортировка timestamp, cursor-пагинация
                                (см. .specs/query-api/)
```

## Event model

| Поле      | Тип     | Описание                                             |
|-----------|---------|------------------------------------------------------|
| timestamp | Instant | Когда произошло — выставляет только сервер           |
| actor     | String  | Кто инициировал: user id или service account         |
| action    | String  | Что сделал: resource.updated, user.login и т.п.      |
| resource  | String  | Над чем: project:42, invoice:777                     |
| outcome   | Enum    | Результат: success / denied / error                  |
| context   | JSONB   | Произвольные детали                                  |

Обязательные поля при приёме: `actor`, `action`, `resource`, `outcome`.
`timestamp` клиент не передаёт — сервер выставляет сам.

## Invariants

Жёсткие ограничения, не обсуждаются:

- **Append-only** — никаких UPDATE и DELETE ни в репозиториях, ни в миграциях
- **Server-side timestamp** — `timestamp` выставляется только на сервере, никогда из тела запроса
- **actor обязателен** — событие без актора не принимается

### What NOT to do

- Не добавлять soft delete (`deleted_at`, `is_deleted`) — нарушает append-only
- Не мокать базу в интеграционных тестах — только Testcontainers
- Не принимать `timestamp` от клиента даже если он его прислал — игнорировать
- не дублируй
- не придумывай

### What to do

- Пиши чистый человекочитаемый код
- Документируй методы
- используй best practices
- делай то что указано в задаче
- после выполнения задачи проверь себя на тестах
- Если что-то непонятно, перед выполнением задай вопросы
- Если какая-то проблема возникла и спустя пару попыток не получилось решить, напиши в чат
- Сенситив дата в .env и подтягивается через application.yml
- перед коммитом - прогнать gradle spotless
## Architectural rules

- Retention policy: хранение N дней, потом archival
- Tamper-evidence: hash chain по событиям

## Stack

- Java 21, Spring Boot 3, Gradle Groovy DSL
- PostgreSQL + Flyway-миграции
- Testcontainers для интеграционных тестов

## CI / CD

- Github actions
