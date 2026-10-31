# OBS scoreboard design — issue 135

Source: https://github.com/UltiStatsDev/ultistats-backend/issues/135 (approved 2026-10-31). Technical decisions delegated; execution is inline, no renewed product approval.

## Approved requirements (complete issue)

## Цель и статус

Отдельный читаемый scoreboard выбранного матча поверх трансляции, аналогично существующему OBS Event Log. Требования утверждены пользователем через PO 2026-10-31. Это будущий backlog: реализацию начинать после технической готовности текущей backend-очистки #125/#127–131, обязательных callbacks и согласования готовности тимлидов. Незавершённая отдельная QA-приёмка Event Log не блокирует независимую новую фичу.

Владелец головной issue — Backend: существующие OBS admin/overlay уже находятся в этом репозитории. Переиспользовать их, не создавать дублирующую OBS-платформу. Изменения React Web UI нужны только если следуют из утверждённого пользовательского потока; если кодовые изменения Web не нужны, тимлид Web явно подтверждает N/A и готовность интеграции вместо фиктивных задач.

## Источник и оформление

- Самостоятельный read-only OBS Browser Source с отдельной стабильной ссылкой на конкретный matchId. Выбор другого матча в админке не переключает уже открытые источники.
- Рекомендуемый размер 1000×160 px; оператор размещает и масштабирует источник средствами OBS.
- Горизонтальная полоса: логотип и название левой команды, центральный счёт, название и логотип правой команды. Часы на небольшой плашке под счётом. Боковые плашки — цвета команд, центр и часы — тёмные; контрастный читаемый текст. Пространство вокруг прозрачно.
- Длинные и кириллические названия не перекрывают счёт/логотипы и не ломают компоновку.
- Существующее изображение команды целиком вписано без обрезания эмблемы. Если изображения нет — аккуратные инициалы. Автоцвета по подходу Event Log, запасные цвета при отсутствии/непригодности изображения, ручное изменение и автоматическая читаемость текста.

## Эфирный профиль и настройка

- Отдельный сохраняемый профиль scoreboard для каждого матча, общий для всех его открытых источников; переживает перезапуск сервиса. Профили других матчей и оформление Event Log не затрагиваются.
- Короткие эфирные названия и цвета не меняют исходные данные команд. По умолчанию порядок команд матча; перестановка переносит название/логотип/цвет/счёт команды вместе.
- Настройка в том же OBS-интерфейсе, что Event Log: выбор матча, редактирование профиля, точный предпросмотр и копирование URL.
- Предпросмотр отражает изменения сразу; эфир — только после «Сохранить», без reload источников.
- Сброс возвращает исходные названия, автоматические цвета и исходный порядок. Результат сброса можно проверить в preview до сохранения.

## Время

- Прошедшее время от фактического старта матча, включая таймауты и перерыв; формат ММ:СС, минуты растут после часа (63:12). Обновление каждую секунду.
- До старта 00:00. После завершения фиксированная длительность. Часы не стартуют заново при загрузке; reload/reconnect восстанавливают актуальное время.
- После разрешённого исправления результата завершённого матча счёт обновляется, часы остаются зафиксированы по длительности матча.

## Серверное состояние и связь

- Счёт берётся только из принятых сервером событий. Локальные неотправленные действия становятся видимыми после синхронизации.
- Гол, undo, update, batch и reconnect приводят табло к актуальному серверному состоянию без ручного reload.
- До старта: команды / 0:0 / 00:00. После завершения итоговый счёт и остановленные часы сохраняются. Последующее разрешённое исправление результата автоматически обновляет открытый источник.
- При исправной связи счёт обновляется не позднее 2 секунд после успешного сохранения события на сервере. Новое открытие/reload восстанавливает актуальный счёт и время.
- При потере связи сохраняется последнее табло. Для последнего известного IN_PROGRESS часы продолжаются. После восстановления сверка с сервером корректирует счёт/статус/часы; если матч завершился за время обрыва, часы останавливаются на правильной длительности.
- Соединение/ошибки видны в настройках, не в эфирном source. При первом открытии без доступных данных, неверном или несуществующем матче source прозрачен; причина показана в админке.

## Приёмка / Definition of Done

- [ ] Реальный OBS Browser Source и отдельная браузерная сессия, изолированное окружение, синтетические данные, точные SHA и evidence.
- [ ] Прозрачность, компоновка 1000×160, длинные/кириллические названия, изображения без обрезания, инициалы, авто/ручные цвета и контраст.
- [ ] Одновременная работа scoreboard и Event Log без взаимного влияния.
- [ ] Preview, сохранение, сброс и перестановка не меняют исходные данные игры и команд.
- [ ] Два разных матча и несколько источников одного матча: изоляция профилей, синхронное применение сохранения, preview-only несохранённых правок/сброса, persistence после restart.
- [ ] PLANNED → IN_PROGRESS → FINISHED; старт при уже открытом source; wall time включает паузы/перерыв; больше 60 минут; reload; поздняя разрешённая коррекция результата.
- [ ] Гол/undo/update/batch, потеря и восстановление связи, неверный/недоступный матч; подтверждён SLA ≤2с при исправной связи.
- [ ] Пройдены проверки соответствующих репозиториев и CI; есть инструкция подключения и поддержанных размеров.
- [ ] После технической готовности обеих команд (либо явного Web N/A) QA проверил общий сценарий; PO подтвердил результат.

## Связанные задачи и правила закрытия

Минимальную техническую декомпозицию и реальные межкомандные зависимости согласуют тимлиды после текущих пакетов. Ссылки на backend/Web/QA issues добавляются по мере оформления без дублирования работы. До успешной общей QA-проверки и решения PO головная и связанные продуктовые issues остаются открытыми. PR используют Refs, не Closes/Fixes; техническая готовность отмечается отдельно.

В каждой межкомандной технической issue обязателен раздел «Обязательный callback смежной команде»: выполняющий тимлид, получатель по роли, полная ссылка на зависимую issue, условие merge/проверок/реальной доступности и обязательная отправка issue/PR/SHA/контракта/инструкции проверки с сигналом возобновить работу. Callback не ждёт общего QA.


## Audited implementation and decisions

Base 6ce9aad4081b10a3fe646d0358641e8b59c2bf1d. Match.startedAt/endedAt are persisted instants; status derives from them. MatchServiceImpl.startMatch/endMatch validate under match row lock. End publishes MatchFinishedSignal, event mutations publish EventLogChangedSignal AFTER_COMMIT. Start and planned metadata changes currently publish nothing. match_teams.score is recalculated transactionally from accepted events and match roster attribution. Use that committed cache, never local event queues. No clock migration or inferred timestamps.

Create an OBS-only canonical snapshot GET /api/v1/obs/scoreboard/{matchId}: matchId, teams[{teamId,teamName,photoUrl,score}], status, startedAt, endedAt, serverNow, profile. A read-only REPEATABLE_READ transaction reads persisted match and ordered match_teams/team photos plus profile; serverNow anchors a monotonic browser elapsed timer despite workstation clock skew. Missing/deleted match is 404, malformed UUID 400. GET has no side effects, no-store.

PUT /api/v1/obs/scoreboard/{matchId}/profile fully replaces {shortNames: UUID->String, teamColors: UUID->#RRGGBB, swapped:Boolean}, last writer wins; maps only accept the two current match teams, names nonblank <=80 UTF-16 chars, maps <=2, null entries fail, null/default fields handled by Jackson/validation. Empty maps and swapped=false reset. A V8 FK-keyed profile table stores TEXT JSON. Save locks match row, serializing profile saves and team changes; SELECT then INSERT/UPDATE is portable H2/PG. It never edits game/team/EventLog/global OBS data. Publish ScoreboardProfileChanged AFTER_COMMIT, with matchId. Add MatchChangedSignal for successful start and metadata update; only OBS handles it, existing v1/v2 SSE and queues unchanged.

SharedWorker continues to own the sole OBS SSE. Add match-changed and scoreboard-profile-changed routing by matchId. Scoreboard watches indefinitely, including FINISHED, for late corrections; no terminal unsubscribe. Serialized Reconciler handles failures, reconnect bootstrap and overlapping invalidations. A 1s REST safety reconciliation covers unavailable notifications and lifecycle recovery, with at most one read in flight; no new per-source SSE. Bounded requests/timeouts and disposal avoid hung reads retaining state forever.

Create obs-scoreboard.html and focused scoreboard model/renderer/source/admin modules. View uses 1000x160 intrinsic layout scaled uniformly to viewport, transparent outer space, side team plates, contain logos/initials, dark center/clock. textContent prevents markup injection; ellipsis and constrained flex/grid keep long names away from score/logos. Existing colors.mjs provides imageAccent/teamAccent/readableText. Clock uses persisted start/end, monotonic advance from serverNow, floor seconds, nonnegative, unbounded minutes; includes timeout/halftime by design.

Extend existing obs-admin.html with separate scoreboard section bound to the existing match selector. Isolated draft state and iframe postMessage (same origin and expected source checks) preview exact renderer; edits/swap/reset immediately re-render preview only. PUT is the only live mutation. Source URL retains matchId regardless of future admin selection. Async selection and image races are guarded. Connections/errors only in admin; initial source stays empty until valid snapshot. Existing Event Log settings, renderer and behavior stay intact. Backend public origin hosts both source/admin; Web React scope N/A, no proxy work.

## Validation and handoff

Baseline own full JVM build and real JS26 PASS. Meaningful RED->GREEN tests: DB/API validation/isolation/rollback/AFTER_COMMIT, start lifecycle, clock and draft model, shared worker routing, real browser layouts and two-match multi-source+EventLog scenarios, SLA <=2s, outage/reconnect, terminal correction. Full build+JS+actual PR CI. Real PG runs Flyway V1..V8, persistence/restart and rollback delivery checks. Synthetic runtime separate ports coordinated with lead; immutable exact-SHA jar/hash/assets/fixtures/run commands for permanent native OBS QA. Browser evidence never claims native OBS acceptance. One PR Refs135, no self merge, issue stays open pending QA/PO.
