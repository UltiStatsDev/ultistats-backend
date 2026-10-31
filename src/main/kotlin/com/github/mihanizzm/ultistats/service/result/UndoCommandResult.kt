package com.github.mihanizzm.ultistats.service.result

import com.github.mihanizzm.ultistats.dto.response.UndoCommandResponse
import com.github.mihanizzm.ultistats.validation.match.MatchProblem

sealed interface UndoCommandResult {
    data class Recorded(val response: UndoCommandResponse) : UndoCommandResult
    data object NotFound : UndoCommandResult
    data class Invalid(val problem: MatchProblem) : UndoCommandResult
    data class Conflict(val problem: MatchProblem) : UndoCommandResult
}
