package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class PossessionChangeWrites(
    @Value("\${ultistats.possession-change.write-enabled:false}") val enabled: Boolean,
) {
    fun rejection(type: EventType): MatchProblem? =
        if (!enabled && type == EventType.POSSESSION_SET) MatchProblem(
            MatchProblemCode.POSSESSION_CHANGE_WRITE_DISABLED,
            "Possession change writes disabled",
            "Writing POSSESSION_SET events is disabled",
        ) else null
}
