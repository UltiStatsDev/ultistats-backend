package com.github.mihanizzm.ultistats.dto.response

import com.github.mihanizzm.ultistats.model.Player
import java.util.UUID

data class PlayerDetailResponse(
    val id: UUID,
    val firstName: String,
    val lastName: String,
    val photoUrl: String?,
    val memberships: List<PlayerTeamMembershipResponse>,
) {
    companion object {
        fun from(player: Player, memberships: List<PlayerTeamMembershipResponse>) = PlayerDetailResponse(
            id = player.id,
            firstName = player.firstName,
            lastName = player.lastName,
            photoUrl = player.photoUrl,
            memberships = memberships,
        )
    }
}
