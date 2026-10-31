package com.github.mihanizzm.ultistats.export

import com.github.mihanizzm.ultistats.dto.response.statistics.DefenseStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.MatchStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.MatchTimeStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.ParticipantStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.PlayerAttackStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.PlayerTimeStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.TeamAttackStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.TeamStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.TeamTimeStatisticsResponse
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.ZipInputStream

@Suppress("NonAsciiCharacters")
class StatisticsZipExporterTest {
    private val exporter = StatisticsZipExporter()

    @Test
    fun `Архив содержит нормализованные таблицы и общую статистику матча`() {
        val response = MatchStatisticsResponse(
            matchId = MATCH_ID,
            teams = emptyList(),
            time = MatchTimeStatisticsResponse(
                totalTimeMs = 120_000,
                betweenPointsTimeMs = 15_000,
                timeoutTimeMs = 30_000,
                halftimeTimeMs = 45_000,
                pureGameTimeMs = 30_000,
            ),
        )

        val entries = readEntries(exporter.export(response))

        assertThat(entries.keys).containsExactly("match.csv", "teams.csv", "participants.csv")
        assertThat(entries.getValue("match.csv")).isEqualTo(
            "match_id,total_time_ms,between_points_time_ms,timeout_time_ms,halftime_time_ms,pure_game_time_ms\r\n" +
                "$MATCH_ID,120000,15000,30000,45000,30000\r\n",
        )
    }

    @Test
    fun `Командная таблица содержит идентификаторы статистику и корректно экранированное имя`() {
        val response = MatchStatisticsResponse(
            matchId = MATCH_ID,
            teams = listOf(
                TeamStatisticsResponse(
                    teamId = TEAM_ID,
                    teamName = "Команда, \"А\"\nЮниоры",
                    attack = TeamAttackStatisticsResponse(
                        score = 15,
                        completePasses = 120,
                        allPasses = 135,
                        pulls = 8,
                        bricks = 1,
                        possessions = 25,
                    ),
                    defense = DefenseStatisticsResponse(
                        blocks = 8,
                        blocksAsMarker = 5,
                        blocksAsFieldPlayer = 3,
                        interceptions = 2,
                        callahans = 1,
                    ),
                    time = TeamTimeStatisticsResponse(
                        possessionTimeMs = 930_000,
                        betweenPointsTimeMs = 70_000,
                        timeoutTimeMs = 60_000,
                    ),
                    participants = emptyList(),
                ),
            ),
            time = MatchTimeStatisticsResponse(1_200_000, 70_000, 60_000, 0, 1_070_000),
        )

        val teamsCsv = readEntries(exporter.export(response)).getValue("teams.csv")

        assertThat(teamsCsv).isEqualTo(
            "match_id,team_id,team_name,score,complete_passes,all_passes,pulls,bricks,possessions,blocks,blocks_as_marker,blocks_as_field_player,interceptions,callahans,possession_time_ms,between_points_time_ms,timeout_time_ms\r\n" +
                "$MATCH_ID,$TEAM_ID,\"Команда, \"\"А\"\"\nЮниоры\",15,120,135,8,1,25,8,5,3,2,1,930000,70000,60000\r\n",
        )
    }

    @Test
    fun `Таблица участников сохраняет игрока неизвестный слот и все показатели`() {
        val player = ParticipantStatisticsResponse(
            participantId = PLAYER_ID,
            kind = MatchParticipantKind.PLAYER,
            unknownSlot = null,
            firstName = "Иван",
            lastName = "Иванов",
            displayName = "Иван, \"Молния\"\nИванов",
            number = 17,
            attack = PlayerAttackStatisticsResponse(11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21),
            defense = DefenseStatisticsResponse(22, 23, 24, 25, 26),
            time = PlayerTimeStatisticsResponse(27_000, 28_000, true),
        )
        val unknown = ParticipantStatisticsResponse(
            participantId = UNKNOWN_ID,
            kind = MatchParticipantKind.UNKNOWN,
            unknownSlot = 2,
            firstName = null,
            lastName = null,
            displayName = "Неизвестный игрок 2",
            number = null,
            attack = PlayerAttackStatisticsResponse(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            defense = DefenseStatisticsResponse(0, 0, 0, 0, 0),
            time = PlayerTimeStatisticsResponse(0, 0),
        )
        val response = MatchStatisticsResponse(
            matchId = MATCH_ID,
            teams = listOf(
                TeamStatisticsResponse(
                    teamId = TEAM_ID,
                    teamName = "Команда 1",
                    attack = TeamAttackStatisticsResponse(0, 0, 0, 0, 0, 0),
                    defense = DefenseStatisticsResponse(0, 0, 0, 0, 0),
                    time = TeamTimeStatisticsResponse(0, 0, 0),
                    participants = listOf(player, unknown),
                ),
            ),
            time = MatchTimeStatisticsResponse(0, 0, 0, 0, 0),
        )

        val participantsCsv = readEntries(exporter.export(response)).getValue("participants.csv")

        assertThat(participantsCsv).isEqualTo(
            "match_id,team_id,team_name,participant_id,kind,unknown_slot,first_name,last_name,display_name,number,passes,catches,assists,goals,drops_on_marker,drops_on_field,incomplete_passes,callahan_drops,disc_possessions,pulls,bricks,blocks,blocks_as_marker,blocks_as_field_player,interceptions,callahans,possession_time_ms,average_possession_time_ms,average_possession_time_available\r\n" +
                "$MATCH_ID,$TEAM_ID,Команда 1,$PLAYER_ID,PLAYER,,Иван,Иванов,\"Иван, \"\"Молния\"\"\nИванов\",17,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27000,28000,true\r\n" +
                "$MATCH_ID,$TEAM_ID,Команда 1,$UNKNOWN_ID,UNKNOWN,2,,,Неизвестный игрок 2,,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,false\r\n",
        )
    }

    private fun readEntries(content: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(content)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    companion object {
        private val MATCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000100")
        private val TEAM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private val UNKNOWN_ID = UUID.fromString("00000000-0000-0000-0000-000000000011")
    }
}
