# Ultimate Frisbee Stats

**Ultimate Frisbee Stats** — веб-приложение для ведения статистики по алтимат-фрисби в реальном времени.
Система ориентирована на стафф матча: быстрый ввод событий, пересчёт статистики и API для интеграций.

## Лицензия

Проект распространяется по [PolyForm Noncommercial License 1.0.0](LICENSE).

Разрешены некоммерческое использование, изменение и распространение проекта. Коммерческое использование требует отдельного разрешения правообладателя.

---

## Быстрый старт

Приложение использует PostgreSQL как единственное хранилище данных. Самый простой запуск — через Docker Compose:

```bash
docker compose up -d
```

После запуска:
- **API:** http://localhost:8080/api/v1/
- **Swagger UI:** http://localhost:8080/swagger-ui.html

Остановка:

```bash
docker compose down
```

Обычный `docker compose down`, перезапуск и пересоздание контейнера сохраняют
данные PostgreSQL и загруженные фотографии в именованных volumes
`postgres_data` и `uploads_data`.

Чтобы безвозвратно удалить базу данных и загруженные фотографии:

```bash
docker compose down -v
```

---

## Локальный запуск без Docker Compose

Сначала поднимите PostgreSQL:

```bash
docker run -d --name ultistats-postgres \
  -e POSTGRES_DB=ultistats \
  -e POSTGRES_USER=ultistats \
  -e POSTGRES_PASSWORD=ultistats \
  -p 5432:5432 \
  -v ultistats_postgres_data:/var/lib/postgresql/data \
  postgres:16-alpine
```

Затем запустите приложение:

```bash
./gradlew bootRun
```

Настройки подключения можно переопределить переменными окружения:

| Переменная | По умолчанию | Описание |
|------------|--------------|----------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/ultistats` | JDBC URL |
| `DATABASE_USERNAME` | `ultistats` | Имя пользователя |
| `DATABASE_PASSWORD` | `ultistats` | Пароль |
| `APP_STORAGE_ROOT` | `uploads` | Директория загруженных файлов |

---

## Резервное копирование данных матча

Для согласованной резервной копии остановите только приложение, оставив
PostgreSQL запущенным. Для локального Compose имя сервиса приложения —
`ultistats`:

```bash
mkdir -p backups
docker compose stop ultistats
docker compose exec -T postgres \
  pg_dump --username ultistats --dbname ultistats --format custom \
  > backups/ultistats.dump
```

Узнайте фактическое имя uploads volume и сохраните его содержимое:

```bash
APP_CONTAINER="$(docker compose ps --all -q ultistats)"
UPLOADS_VOLUME="$(docker inspect "$APP_CONTAINER" \
  --format '{{range .Mounts}}{{if eq .Destination "/app/uploads"}}{{.Name}}{{end}}{{end}}')"
test -n "$UPLOADS_VOLUME"
docker run --rm \
  --volume "$UPLOADS_VOLUME:/data:ro" \
  --volume "$PWD/backups:/backup" \
  alpine:3.20 \
  tar -czf /backup/uploads.tar.gz -C /data .
docker compose start ultistats
```

Для production выполняйте те же действия из `/opt/ultistats`, заменив команды
`docker compose` на `docker compose --env-file .env -f compose.prod.yml`, а имя
сервиса `ultistats` — на `app`. Перед изменением production deployment уже
создаёт PostgreSQL dump и tar-снимок непустой директории `/app/uploads` в
`/opt/ultistats/backups`.

### Восстановление

Восстановление заменяет текущее состояние и должно выполняться только при
остановленном приложении. Сначала сохраните дополнительную резервную копию.

```bash
docker compose stop ultistats
docker compose exec -T postgres \
  pg_restore --clean --if-exists --exit-on-error --no-owner \
  --username ultistats --dbname ultistats \
  < backups/ultistats.dump

APP_CONTAINER="$(docker compose ps --all -q ultistats)"
UPLOADS_VOLUME="$(docker inspect "$APP_CONTAINER" \
  --format '{{range .Mounts}}{{if eq .Destination "/app/uploads"}}{{.Name}}{{end}}{{end}}')"
test -n "$UPLOADS_VOLUME"
docker run --rm \
  --volume "$UPLOADS_VOLUME:/data" \
  --volume "$PWD/backups:/backup:ro" \
  alpine:3.20 \
  sh -c 'find /data -mindepth 1 -delete && tar -xzf /backup/uploads.tar.gz -C /data'
docker compose start ultistats
```

Для production используйте тот же вариант команды Compose и сервис `app`, что
и при резервном копировании. После восстановления проверьте Swagger, получение
фотографий через `/uploads/**` и данные завершённого матча.

---

## Основные возможности

- Фиксация игровых событий в реальном времени: пасы, голы, потери, блоки, перехваты, кэллаханы и др.
- Персональная и командная статистика.
- Ведение лога событий с привязкой ко времени.
- Редактирование событий матча.
- API-документация через Swagger/OpenAPI.
- Персистентное хранение данных в PostgreSQL.
- Миграции БД через Flyway.

---

## Технологический стек

- **Backend:** Kotlin, Spring Boot 3, Gradle
- **Persistence:** Spring Data JPA, PostgreSQL, Flyway
- **Тесты:** JUnit 5, Spring MockMvc
- **Документация API:** springdoc-openapi
- **Контейнеризация:** Docker, Docker Compose

### Проверка списков на PostgreSQL

Обычный `./gradlew cleanTest build` использует H2. Для проверки SQL-пагинации,
фильтров, COUNT, NULL, Unicode и UUID-порядка на PostgreSQL 16 запустите
отдельную тестовую базу и выполните:

```bash
SPRING_PROFILES_ACTIVE=postgres-list \
TEST_POSTGRES_URL=jdbc:postgresql://localhost:54663/issue63 \
TEST_POSTGRES_USERNAME=postgres \
TEST_POSTGRES_PASSWORD=your-test-password \
./gradlew cleanTest test --tests '*ListPaginationIntegrationTest' \
  --tests '*ListSortingControllerTest' --tests '*ListQueryValidationControllerTest'
```

Профиль `postgres-list` применяет настоящие Flyway migrations. Используйте
выделенную тестовую базу: тесты создают и удаляют данные. Сортировка строк
сохраняет регистр и порядок UTF-16 Kotlin; NULL идут последними при ASC и
первыми при DESC. При равных значениях UUID всегда сравниваются по ASC,
как `java.util.UUID.compareTo` (оба 64-битных блока сравниваются со знаком).
SQL выбирает только текущую страницу, общее количество считает отдельным
COUNT с теми же фильтрами.

---

## Пагинация и фильтрация списков

Списки команд, игроков и матчей принимают `page` (целое число от 0, по умолчанию 0)
и `size` (целое число от 1 до 100, по умолчанию 20). Страница за пределами
результатов возвращает пустой `content`, сохраняя общее количество записей.

Фильтры команд: `name`; игроков: `name`, `teamId`; матчей: `teamId`, `status`,
`dateFrom`, `dateTo`. Поиск по имени/названию использует частичное совпадение.
`teamId` — UUID, `status` — `PLANNED`, `IN_PROGRESS` или `FINISHED`.

`dateFrom` и `dateTo` задают включительный диапазон **планового начала матча**
в формате ISO 8601, например `2026-01-02T12:00:00Z`. Можно передать одну границу;
при двух границах `dateFrom` не должен быть позже `dateTo`. Матчи без планового
начала исключаются, если задана хотя бы одна граница даты.

Необязательные параметры можно опустить. Переданный параметр должен иметь ровно
одно непустое значение: пустые/пробельные значения, повторы, формы вроде `page[]`
и `!page`, недопустимые типы и диапазоны возвращают `400 application/problem+json`
с кодом `INVALID_REQUEST` и указанием параметра в `detail`.

## Сортировка списков

Списки команд, игроков и матчей принимают отдельные query-параметры:

```http
GET /api/v1/teams?sortBy=NAME&sortDirection=DESC
GET /api/v1/players?sortBy=FIRST_NAME&sortDirection=ASC
GET /api/v1/matches?sortBy=STARTED_AT&sortDirection=DESC
```

| Ресурс | Значения `sortBy` | Поле по умолчанию |
|--------|------------------|------------------|
| `/api/v1/teams` | `NAME` | `NAME` |
| `/api/v1/players` | `LAST_NAME`, `FIRST_NAME` | `LAST_NAME` |
| `/api/v1/matches` | `PLANNED_START_TIMESTAMP`, `STARTED_AT`, `ENDED_AT`, `STATUS` | `PLANNED_START_TIMESTAMP` |

`sortDirection` — `ASC` (по умолчанию) или `DESC`. Новые значения регистрозависимы.
Параметры необязательны: одно направление применяется к полю по умолчанию,
одно поле — с направлением `ASC`. Сортировка выполняется до пагинации;
существующие фильтры, `page` и `size` не меняются. Для дат `null` остаётся
в конце при `ASC` и в начале при `DESC`; статусы сортируются по имени.

Старый `sort=lastName:desc` пока поддерживается, но помечен deprecated в Swagger.
В нём сохраняются прежние имена полей (`name`, `lastName`, `firstName`,
`plannedStartTimestamp`, `startedAt`, `endedAt`, `status` — только для своего ресурса),
необязательное направление и его регистронезависимость.
Нельзя совмещать `sort` с `sortBy` или `sortDirection`.
Неизвестные поля/направления, пустые и повторяющиеся параметры, некорректный
старый формат возвращают `400 application/problem+json` с кодом `INVALID_REQUEST`.

## Структура событий

- **Атака:** пуллы, передачи, голевые пасы, голы, потери, спасения.
- **Защита:** блок на маркере, блок в поле, перехват, кэллахан.
- **Системные:** таймауты, начало/конец перерыва.

Каждое событие содержит тип, время, команду и связанных игроков, если они нужны для выбранного типа события.

---

## Offline-first Events API v2

`GET /api/v2/matches/{matchId}/events` возвращает согласованный снимок:
`events` в порядке `sequenceNumber`, `lastSequenceNumber` (включая номера
удалённых событий) и `logRevision`. Для нового batch передайте оба значения
в `POST /api/v2/matches/{matchId}/events/batch`:

```json
{
  "expectedLastSequenceNumber": 0,
  "expectedLogRevision": 0,
  "events": [
    {
      "eventId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
      "type": "PULL",
      "occurredAt": "2026-07-28T12:00:00Z",
      "participantId": "11111111-1111-1111-1111-111111111111"
    }
  ]
}
```

Замените UUID участника на `participantId` из состава матча. Каждый элемент
использует payload v1, но клиентский `eventId` обязателен. Допускается 1–100
событий. Сервер сохраняет их атомарно под lock матча и назначает номера
в порядке массива. Одинаковые timestamps допустимы; время назад — конфликт.

Новый batch возвращает `201`; точный повтор с исходными базами, UUID,
payload и порядком — `200`, исходные события и актуальную позицию журнала.
Последующие append не мешают retry. Любой успешный PATCH, DELETE или undo
после исходного batch инвалидирует его retry, включая PATCH без изменения
значений. Ревизия растёт один раз на команду изменения, не на каждый элемент;
retry её не увеличивает. Переставленный, частично сохранённый или сокращённый
batch не применяется.

При `409` остановите отправку зависимого хвоста, загрузите GET v2 и предложите
оператору reconciliation. `ProblemDetail` содержит `code`, `lastSequenceNumber`,
`logRevision`, а при ошибке конкретного события — `eventId` и нулевой
`eventIndex`. Основные коды: `EVENT_LOG_STALE`, `EVENT_BATCH_CONFLICT`,
`EVENT_IDEMPOTENCY_CONFLICT`, `EVENT_SEQUENCE_VIOLATION`; действуют также
существующие lifecycle/timestamp коды. Ошибки формы, отсутствующие UUID,
повтор UUID внутри запроса и неверные участники возвращают `400`.

Events API v1 остаётся поддерживаемым и **не deprecated**. Backend v2 не означает,
что frontend уже мигрировал или прошёл полевые матчи: эти этапы #71 выполняются
отдельно; deprecated для v1 допустим только после их успешного завершения.

## Отмена последнего события

```http
POST /api/v1/matches/{matchId}/events/undo
```

Тело запроса не требуется. Сервер выбирает последнее **активное** событие по
`sequenceNumber` (не по `occurredAt`) и мягко удаляет его под блокировкой матча.
Счёт пересчитывается в той же транзакции. После commit подписчики SSE получают
`event-log-changed` с `operation=DELETED` и идентификатором отменённого события.

- `200 OK` — отменённое событие в обычном формате `EventResponse`, включая `id`,
  `sequenceNumber`, `type`, `occurredAt` и поля участников/команды.
- `404 Not Found` — матч отсутствует/удалён либо активных событий больше нет.
- `409 Conflict` — отмена нарушает правила состояния или последовательности событий.
  Например, последний гол завершённого матча отменить нельзя: его журнал должен
  оканчиваться завершённым розыгрышем. Матч автоматически не открывается заново.

Ошибки возвращаются в формате `application/problem+json`. Удалённые события
пропускаются при следующей отмене; номера остальных событий сохраняются,
а новые события получают новый номер без повторного использования старых.

**Операция неидемпотентна:** каждый успешный вызов отменяет ещё одно событие.
Не включайте автоматический retry при потере ответа. Сначала перечитайте журнал
через `GET /api/v1/matches/{matchId}/events` и сверяйте состояние интерфейса.

## OBS diagnostics

Для диагностической Browser Source страницы OBS откройте:

```text
http://<ultistats-host>:8080/obs-diagnostic.html?matchId=<uuid>
```

Страница сначала получает существование матча, затем подключается к SSE-потоку и только после события `connected` загружает начальный event log. Первая загрузка устанавливает тихую baseline-точку: она не воспроизводит историю как изменения. При переподключении страница сверяет свежий REST event log с сохраненной baseline-точкой и показывает пропущенные изменения; полная перезагрузка страницы также не воспроизводит историю. Событие `match-finished` закрывает SSE-поток после финальной REST-сверки.

Это диагностическая страница, а не финальный broadcast overlay. Текущий MVP-броадкастер process-local: для нескольких экземпляров приложения потребуются broker/outbox. Перед публичным размещением следует добавить read-only token для overlay.

---

## Production deployment

После merge в `master` workflow `.github/workflows/cd.yml`:

1. собирает и тестирует приложение на Java 21;
2. публикует Linux AMD64 image в private GHCR с тегами commit SHA и `latest`;
3. копирует на сервер только `deploy/compose.prod.yml` и `deploy/deploy.sh`;
4. создаёт PostgreSQL dump и резервную копию загруженных файлов;
5. до замены application-контейнера переносит старые файлы в новый persistent
   volume, затем разворачивает SHA-tagged image и проверяет `/v3/api-docs` через
   Caddy;
6. при ошибке переноса сохраняет текущий контейнер, а при ошибке health check
   возвращает предыдущий application image.

Перед резервным копированием скрипт требует минимум 1 GiB свободного места и
хранит не более 10 комплектов `pre-*.dump` и соответствующих
`pre-*-uploads.tar`. При повторных деплоях непустой uploads volume не
перезаписывается. Порог и retention можно переопределить серверными переменными
`DEPLOY_MIN_FREE_KB` и `DEPLOY_BACKUP_RETENTION`.

Авторитетная версия production — полный SHA-тег вида:

```text
ghcr.io/ultistatsdev/ultistats-backend:<40-character-commit-sha>
```

`latest` предназначен только для удобства и не используется для деплоя или rollback.

### GitHub Environment

В репозитории нужен Environment `production` с секретами:

| Секрет | Значение |
|--------|----------|
| `PROD_HOST` | DNS-имя или IP production VM |
| `PROD_USER` | SSH-пользователь (`appuser`) |
| `PROD_SSH_PRIVATE_KEY` | приватная часть отдельного deploy-only SSH-ключа |
| `PROD_SSH_KNOWN_HOSTS` | заранее проверенная строка host key для production VM |

Workflow использует встроенный `GITHUB_TOKEN` только для публикации image. Registry token сервера в GitHub Actions не передаётся.

### Однократная подготовка VM

Публичную часть отдельного deploy-ключа добавьте в `/home/appuser/.ssh/authorized_keys`. Сверьте fingerprint SSH host key по доверенному каналу и сохраните соответствующую строку `known_hosts` в `PROD_SSH_KNOWN_HOSTS`.

На VM создайте `/opt/ultistats/.env` с правами `0600`:

```dotenv
POSTGRES_PASSWORD=<existing-production-password>
APP_IMAGE=ghcr.io/ultistatsdev/ultistats-backend:<currently-deployed-sha>
```

Приватный GHCR image VM скачивает по отдельному token с минимальным `read:packages` scope:

```bash
printf '%s' "$GHCR_READ_TOKEN" | docker login ghcr.io \
  --username <github-user> \
  --password-stdin
```

Не добавляйте token в `.env`, Compose или репозиторий. После login он хранится в Docker credentials пользователя `appuser`.

Production layout:

```text
/opt/ultistats/
├── .env
├── Caddyfile
├── compose.prod.yml
├── deploy.sh
├── DEPLOYED_REVISION
└── backups/
```

Swagger production: `http://158.160.219.91/swagger-ui.html`. Машина публикует только Caddy на порту 80; приложение и PostgreSQL доступны лишь во внутренних Docker networks.

### Ручной rollback

Для возврата на известный исправный commit:

```bash
ssh appuser@158.160.219.91
/opt/ultistats/deploy.sh \
  ghcr.io/ultistatsdev/ultistats-backend:<previous-40-character-sha> \
  <previous-40-character-sha>
```

Скрипт перед rollback также создаёт свежий dump и проверяет API. Он пересоздаёт только контейнер приложения и не пересоздаёт PostgreSQL.

Важно: rollback Docker image не отменяет уже применённую Flyway migration. Новые миграции должны быть обратно совместимы с предыдущей версией приложения. Восстановление БД из dump выполняется только вручную после остановки приложения, потому что это деструктивная операция.

---

## Потенциальные будущие разработки

- Интеграция с API Ultisport.
- События для фолов, пиков, диск-спейсов и других объявлений.
- API для OBS-оверлеев.
- Импорт/экспорт матчей и статистики.
- Авторизация и аутентификация.

### Перенос матчей между локальными установками (JSON v1)

`POST /api/v1/matches/export` принимает `{"matchIds":["<UUID>"]}` и скачивает
`ultistats-matches.json` (`application/json`). Порядок выбранных матчей сохраняется;
пустой список или повтор ID возвращает `400`, отсутствующий или удалённый матч — `404`.
Выборка читается из одного согласованного снимка базы.

Полученный JSON можно без преобразования отправить в `POST /api/v1/matches/import`
на другой установке. Формат содержит обязательный `formatVersion: 1`, массивы
`matches`, `teams`, `players`. Полный импортируемый пример опубликован в Swagger.
Матчи содержат `playersPerTeam`, временные отметки, две команды в исходном порядке,
исторические названия и участников, а также актуальный журнал событий.
Статус определяется сохранёнными `startedAt`/`endedAt`: поддерживаются PLANNED,
IN_PROGRESS и FINISHED, включая продолжение незавершённого матча после переноса.

Импорт **всегда создаёт независимые копии** с новыми UUID. Режимов skip/overwrite нет.
Общие команды и игроки остаются общими внутри одного пакета. Повторный импорт
создаёт ещё одну копию. Ответ `200` содержит отображения исходных ID в новые:
`matchIds`, `teamIds`, `playerIds`, `eventIds`, а также
`participantIds[исходныйMatchId][исходныйParticipantId]`, включая UNKNOWN.
Новые PLAYER participant IDs совпадают с новыми ID карточек игроков; UNKNOWN
пересоздаются отдельно в каждом матче. Старые локальные данные не изменяются.

Переносятся карточки только связанных команд/игроков, включая мягко удалённые
исходные карточки; новые карточки активны. При отсутствии карточки игрока используются
имя и фамилия из первого выбранного матча. Исторические имена и номера каждого матча
остаются отдельными снимками и не заменяются актуальными данными карточек.
Фотографии и локальные URL `/uploads/...`, текущие составы команд, удалённые события
и данные идемпотентности запросов **не переносятся**. Поэтому импортированные команды
имеют пустой текущий состав; исторические составы матчей сохраняются полностью.
Счёт пересчитывается из событий. Порядок и номера активных событий сохраняются,
включая промежутки номеров после удаления событий.

Пакет проверяется целиком: версия, обязательные поля и типы, уникальность ID,
полнота каталогов и ссылок, два UNKNOWN-слота каждой команды, номера участников,
категории и принадлежность участников событий, последовательность и время событий,
согласованность начала/окончания матча. Несовместимый или некорректный JSON возвращает
`400 application/problem+json` с `code: INVALID_REQUEST` и местом ошибки в `detail`.
Импорт выполняется одной транзакцией: при ошибке не сохраняется ни один матч или карточка.
Старый формат `PersistenceData` не поддерживается. Существующий ZIP/CSV экспорт
статистики и остальные API остаются доступными.
