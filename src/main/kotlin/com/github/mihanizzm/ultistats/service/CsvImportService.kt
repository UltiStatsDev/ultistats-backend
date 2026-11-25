package com.github.mihanizzm.ultistats.service

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.mihanizzm.ultistats.csvimport.CsvBatch
import com.github.mihanizzm.ultistats.model.Player
import com.github.mihanizzm.ultistats.model.Team
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class CsvImportCounts(val teams: Int, val players: Int, val memberships: Int)
data class CsvTeamMapping(val applicationRow: Int, val teamId: UUID)
data class CsvPlayerMapping(val rosterRow: Int, val playerId: UUID)
@JsonInclude(JsonInclude.Include.ALWAYS)
data class CsvMembershipMapping(val rosterRow: Int, val teamId: UUID, val playerId: UUID, val number: Int?)
data class CsvImportResult(val counts: CsvImportCounts, val teams: List<CsvTeamMapping>,
    val players: List<CsvPlayerMapping>, val memberships: List<CsvMembershipMapping>)

@Service
class CsvImportService(private val teams: TeamService, private val players: PlayerService,
    private val memberships: TeamPlayerService) {
    // Keep this outer boundary in .service. so the existing authority transaction advisor applies.
    @Transactional
    fun create(batch: CsvBatch): CsvImportResult {
        val teamMappings = batch.teams.map {
            val id = UUID.randomUUID()
            teams.create(Team(id, it.name, it.city))
            CsvTeamMapping(it.row, id)
        }
        val playerMappings = mutableListOf<CsvPlayerMapping>()
        val membershipMappings = batch.players.map {
            val id = UUID.randomUUID()
            val teamId = teamMappings[it.teamIndex].teamId
            players.create(Player(id, it.firstName, it.lastName))
            memberships.add(teamId, id, it.number)
            playerMappings += CsvPlayerMapping(it.row, id)
            CsvMembershipMapping(it.row, teamId, id, it.number)
        }
        return CsvImportResult(CsvImportCounts(teamMappings.size, playerMappings.size, membershipMappings.size),
            teamMappings, playerMappings, membershipMappings)
    }
}
