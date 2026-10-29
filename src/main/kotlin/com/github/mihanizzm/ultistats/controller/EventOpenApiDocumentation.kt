package com.github.mihanizzm.ultistats.controller

object EventOpenApiDocumentation {
    const val CREATE_PAYLOAD_TABLE = """
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

"""
}
