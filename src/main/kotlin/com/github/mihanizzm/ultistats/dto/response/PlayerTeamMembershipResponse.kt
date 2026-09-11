package com.github.mihanizzm.ultistats.dto.response

import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.model.TeamPlayer
import java.util.UUID

data class PlayerTeamMembershipResponse(
    val teamId: UUID,
    val teamName: String,
    val playerId: UUID,
    val number: Int?,
) {
    companion object {
        fun from(membership: TeamPlayer, team: Team) = PlayerTeamMembershipResponse(
            teamId = membership.teamId,
            teamName = team.name,
            playerId = membership.playerId,
            number = membership.number,
        )
    }
}
