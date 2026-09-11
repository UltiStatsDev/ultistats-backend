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
docker-compose up -d
```

После запуска:
- **API:** http://localhost:8080/api/v1/
- **Swagger UI:** http://localhost:8080/swagger-ui.html

Остановка:

```bash
docker-compose down
```

Чтобы удалить данные PostgreSQL volume:

```bash
docker-compose down -v
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

---

## Структура событий

- **Атака:** пуллы, передачи, голевые пасы, голы, потери, спасения.
- **Защита:** блок на маркере, блок в поле, перехват, кэллахан.
- **Системные:** таймауты, начало/конец перерыва.

Каждое событие содержит тип, время, команду и связанных игроков, если они нужны для выбранного типа события.

---

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
4. создаёт PostgreSQL dump;
5. разворачивает SHA-tagged image и проверяет `/v3/api-docs` через Caddy;
6. возвращает предыдущий application image, если health check не проходит.

Перед dump скрипт требует минимум 1 GiB свободного места и хранит не более 10 автоматических `pre-*.dump` файлов. Порог и retention можно переопределить серверными переменными `DEPLOY_MIN_FREE_KB` и `DEPLOY_BACKUP_RETENTION`.

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
