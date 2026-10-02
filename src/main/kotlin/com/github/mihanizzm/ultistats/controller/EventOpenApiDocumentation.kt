package com.github.mihanizzm.ultistats.controller

object EventOpenApiDocumentation {
    const val CREATE_DESCRIPTION = """
Создаёт одно событие в журнале матча.

Каждый запрос содержит обязательные поля `type` и `occurredAt`. Идентификаторы участников относятся
к участникам-снэпшотам конкретного матча, а не к глобальным сущностям игроков.

| `type` | Дополнительные обязательные поля | Смысл |
|---|---|---|
| `PASS` | `fromParticipantId`, `toParticipantId` | Успешный пас: бросающий и принимающий из одной команды. |
| `GOAL` | `fromParticipantId`, `toParticipantId` | Голевой пас: бросающий и забивший из одной команды. Завершает поинт. |
| `INCOMPLETE_PASS` | `participantId` | Незавершённый пас; `participantId` — бросающий. |
| `PULL` | `participantId` | Пулл; `participantId` — выполнивший его игрок. |
| `BRICK` | `participantId` | Brick на предыдущем пулле; `participantId` — выполнивший пулл игрок. |
| `PICKUP` | `participantId` | Подбор свободного диска; указанный игрок становится владельцем. |
| `BLOCK` | `fromParticipantId`, `toParticipantId` | Блок без уточнения: бросающий и защитник из разных команд. |
| `BLOCK_MARKER` | `fromParticipantId`, `toParticipantId` | Блок маркером: бросающий и защитник из разных команд. |
| `BLOCK_FIELD` | `fromParticipantId`, `toParticipantId` | Полевой блок: бросающий и защитник из разных команд. |
| `INTERCEPTION` | `fromParticipantId`, `toParticipantId` | Перехват: бросающий и перехвативший защитник из разных команд. |
| `CALLAHAN` | `fromParticipantId`, `toParticipantId` | Кэллахан: бросающий и перехвативший защитник из разных команд. Завершает поинт. |
| `TIMEOUT_START` | `teamId` | Начало таймаута указанной команды. |
| `TIMEOUT_END` | `teamId` | Окончание таймаута указанной команды. |
| `HALFTIME_START` | — | Начало перерыва; дополнительных идентификаторов нет. |
| `HALFTIME_END` | — | Окончание перерыва; дополнительных идентификаторов нет. |

Допустимость события также зависит от текущего состояния матча и последовательности уже записанных событий.
Выберите пример нужного `EventType` в выпадающем списке Swagger UI.
"""

    const val UPDATE_DESCRIPTION = """
Исправляет участников уже существующего события, не меняя его `occurredAt`.

- `type` обязателен и обычно должен совпадать с типом сохранённого события.
- `participantId` обязателен при исправлении события с одним участником.
- Для события с двумя участниками можно передать один или оба поля: `fromParticipantId`, `toParticipantId`.
- `teamId` обязателен при исправлении командного события.
- `BLOCK`, `BLOCK_MARKER` и `BLOCK_FIELD` разрешено заменять друг на друга.
- `HALFTIME_START` и `HALFTIME_END` являются системными событиями и не редактируются; сервер вернёт `405`.
- Другие переходы между типами и payload другой категории недопустимы.
"""

    const val PARTICIPANT_ID = "11111111-1111-1111-1111-111111111111"
    const val SECOND_PARTICIPANT_ID = "22222222-2222-2222-2222-222222222222"
    const val TEAM_ID = "33333333-3333-3333-3333-333333333333"
    const val OCCURRED_AT = "2026-07-28T12:30:00Z"

    const val PASS_EXAMPLE = """{"type":"PASS","occurredAt":"$OCCURRED_AT","fromParticipantId":"$PARTICIPANT_ID","toParticipantId":"$SECOND_PARTICIPANT_ID"}"""
    const val GOAL_EXAMPLE = """{"type":"GOAL","occurredAt":"$OCCURRED_AT","fromParticipantId":"$PARTICIPANT_ID","toParticipantId":"$SECOND_PARTICIPANT_ID"}"""
    const val INCOMPLETE_PASS_EXAMPLE = """{"type":"INCOMPLETE_PASS","occurredAt":"$OCCURRED_AT","participantId":"$PARTICIPANT_ID"}"""
    const val PULL_EXAMPLE = """{"type":"PULL","occurredAt":"$OCCURRED_AT","participantId":"$PARTICIPANT_ID"}"""
    const val BRICK_EXAMPLE = """{"type":"BRICK","occurredAt":"$OCCURRED_AT","participantId":"$PARTICIPANT_ID"}"""
    const val PICKUP_EXAMPLE = """{"type":"PICKUP","occurredAt":"$OCCURRED_AT","participantId":"$PARTICIPANT_ID"}"""
    const val BLOCK_EXAMPLE = """{"type":"BLOCK","occurredAt":"$OCCURRED_AT","fromParticipantId":"$PARTICIPANT_ID","toParticipantId":"$SECOND_PARTICIPANT_ID"}"""
    const val BLOCK_MARKER_EXAMPLE = """{"type":"BLOCK_MARKER","occurredAt":"$OCCURRED_AT","fromParticipantId":"$PARTICIPANT_ID","toParticipantId":"$SECOND_PARTICIPANT_ID"}"""
    const val BLOCK_FIELD_EXAMPLE = """{"type":"BLOCK_FIELD","occurredAt":"$OCCURRED_AT","fromParticipantId":"$PARTICIPANT_ID","toParticipantId":"$SECOND_PARTICIPANT_ID"}"""
    const val INTERCEPTION_EXAMPLE = """{"type":"INTERCEPTION","occurredAt":"$OCCURRED_AT","fromParticipantId":"$PARTICIPANT_ID","toParticipantId":"$SECOND_PARTICIPANT_ID"}"""
    const val CALLAHAN_EXAMPLE = """{"type":"CALLAHAN","occurredAt":"$OCCURRED_AT","fromParticipantId":"$PARTICIPANT_ID","toParticipantId":"$SECOND_PARTICIPANT_ID"}"""
    const val TIMEOUT_START_EXAMPLE = """{"type":"TIMEOUT_START","occurredAt":"$OCCURRED_AT","teamId":"$TEAM_ID"}"""
    const val TIMEOUT_END_EXAMPLE = """{"type":"TIMEOUT_END","occurredAt":"$OCCURRED_AT","teamId":"$TEAM_ID"}"""
    const val HALFTIME_START_EXAMPLE = """{"type":"HALFTIME_START","occurredAt":"$OCCURRED_AT"}"""
    const val HALFTIME_END_EXAMPLE = """{"type":"HALFTIME_END","occurredAt":"$OCCURRED_AT"}"""

    const val PICKUP_PATCH_EXAMPLE = """{"type":"PICKUP","participantId":"$PARTICIPANT_ID"}"""
    const val PASS_PATCH_EXAMPLE = """{"type":"PASS","toParticipantId":"$SECOND_PARTICIPANT_ID"}"""
    const val BLOCK_PATCH_EXAMPLE = """{"type":"BLOCK_MARKER","fromParticipantId":"$PARTICIPANT_ID","toParticipantId":"$SECOND_PARTICIPANT_ID"}"""
    const val TIMEOUT_PATCH_EXAMPLE = """{"type":"TIMEOUT_START","teamId":"$TEAM_ID"}"""
}
