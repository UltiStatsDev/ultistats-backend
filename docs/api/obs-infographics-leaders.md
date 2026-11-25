# Private список лидеров инфографики

`GET /api/v1/obs/infographics/leaders/{matchId}` и `/auto` доступны только по HTTPS с действующей captured staff session и правом OBS_ADMIN (ADMIN и ORGANIZER). Viewer bearer, guest и небезопасный транспорт доступа не дают. GET не меняет состояние, ответ no-store. EXACT принимает полный UUID; удалённый матч — 404. AUTO использует существующий серверный selector; пустой выбор — `{snapshot:null}`.

`/obs-leaders.html` — отдельный read-only список, открываемый из OBS admin. EXACT/AUTO и UUID задаются явно; обновление запускает последовательный bounded polling. Смена binding очищает старый список. Потеря captured session/context завершает чтение и очищает данные; новая сессия требует повторного открытия страницы. Ключ OBS не нужен. Это не управление показом в эфире.

Источник — внутренний `StatisticsFacade.get` в исходной read-only REPEATABLE_READ транзакции вместе с выбором матча/статусом. Участники, имена, номера и названия команд из match snapshot. Только PLAYER участвуют; UNKNOWN и неидентифицированные TEMPORARY не конкурируют. Existing canonical resolver уже складывает same-team identified slots в PLAYER. Не выполняется второй replay и не расширяются права общего statistics HTTP.

Ответ содержит matchId, status, deterministic inputRevision и teams. Каждая команда имеет teamId/name/state и массивы attack/defense; `READY` может иметь пустые массивы, `UNAVAILABLE` означает неконсистентные исходные счётчики. Каждый лидер содержит participantId/name/number/ratingTenths/components. Components дают ordinaryAccuratePasses, ratedGoals, assists, три вида blocks, interceptions/callahans и оба рейтинга. Рейтинг — целое Long в десятых:

- attack = 15 × (goals − callahans) + 15 × assists + (passes − assists);
- defense = 15 × (blocks + blocksAsMarker + blocksAsFieldPlayer) + 20 × interceptions + 75 × callahans.

Assist даёт всего 1.5; Callahan только 7.5 защиты. Публичные counters неизменны. Отрицательные/несогласованные используемые counters не clamp-ятся: команда UNAVAILABLE. Все слагаемые переводятся в Long до арифметики. Независимый положительный максимум каждой команды сохраняет ВСЕ равные результаты в canonical UUID order. Ноль не создаёт лидера. Undo и identification читаются заново из canonical statistics.

inputRevision хеширует structured JSON статуса, logRevision, persisted lineup identity bindings, выбранных roster identities/names/team names и canonical counters всех участников, включая не победивших. serverNow отсутствует: одинаковые входы не меняют revision при опросе. Revision — идентификатор результата чтения; будущие команды показа должны заново проверять актуальный snapshot в своей транзакции.
