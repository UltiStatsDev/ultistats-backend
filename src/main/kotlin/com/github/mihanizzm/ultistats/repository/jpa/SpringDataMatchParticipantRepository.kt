package com.github.mihanizzm.ultistats.repository.jpa

import com.github.mihanizzm.ultistats.model.MatchParticipant
import com.github.mihanizzm.ultistats.model.MatchParticipantId
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataMatchParticipantRepository : JpaRepository<MatchParticipant, MatchParticipantId> {
    fun findAllByMatchId(matchId: UUID): List<MatchParticipant>
    fun findAllByMatchIdAndParticipantIdIn(matchId: UUID, participantIds: Collection<UUID>): List<MatchParticipant>
    fun deleteAllByMatchId(matchId: UUID)
}
