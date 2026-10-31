package com.github.mihanizzm.ultistats.export

import com.github.mihanizzm.ultistats.dto.response.statistics.MatchStatisticsResponse
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Component
class StatisticsZipExporter {
    fun export(statistics: MatchStatisticsResponse): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.writeEntry("match.csv", matchCsv(statistics))
            zip.writeEntry("teams.csv", teamsCsv(statistics))
            zip.writeEntry("participants.csv", participantsCsv(statistics))
        }
        output.toByteArray()
    }

    private fun matchCsv(statistics: MatchStatisticsResponse): String = buildString {
        append("match_id,total_time_ms,between_points_time_ms,timeout_time_ms,halftime_time_ms,pure_game_time_ms\r\n")
        appendCsvRow(
            statistics.matchId,
            statistics.time.totalTimeMs,
            statistics.time.betweenPointsTimeMs,
            statistics.time.timeoutTimeMs,
            statistics.time.halftimeTimeMs,
            statistics.time.pureGameTimeMs,
        )
    }

    private fun teamsCsv(statistics: MatchStatisticsResponse): String = buildString {
        append("match_id,team_id,team_name,score,complete_passes,all_passes,pulls,bricks,possessions,blocks,blocks_as_marker,blocks_as_field_player,interceptions,callahans,possession_time_ms,between_points_time_ms,timeout_time_ms\r\n")
        statistics.teams.forEach { team ->
            appendCsvRow(
                statistics.matchId,
                team.teamId,
                team.teamName,
                team.attack.score,
                team.attack.completePasses,
                team.attack.allPasses,
                team.attack.pulls,
                team.attack.bricks,
                team.attack.possessions,
                team.defense.blocks,
                team.defense.blocksAsMarker,
                team.defense.blocksAsFieldPlayer,
                team.defense.interceptions,
                team.defense.callahans,
                team.time.possessionTimeMs,
                team.time.betweenPointsTimeMs,
                team.time.timeoutTimeMs,
            )
        }
    }

    private fun participantsCsv(statistics: MatchStatisticsResponse): String = buildString {
        append("match_id,team_id,team_name,participant_id,kind,unknown_slot,first_name,last_name,display_name,number,passes,catches,assists,goals,drops_on_marker,drops_on_field,incomplete_passes,callahan_drops,disc_possessions,pulls,bricks,blocks,blocks_as_marker,blocks_as_field_player,interceptions,callahans,possession_time_ms,average_possession_time_ms,average_possession_time_available\r\n")
        statistics.teams.forEach { team ->
            team.participants.forEach { participant ->
                appendCsvRow(
                    statistics.matchId,
                    team.teamId,
                    team.teamName,
                    participant.participantId,
                    participant.kind,
                    participant.unknownSlot,
                    participant.firstName,
                    participant.lastName,
                    participant.displayName,
                    participant.number,
                    participant.attack.passes,
                    participant.attack.catches,
                    participant.attack.assists,
                    participant.attack.goals,
                    participant.attack.dropsOnMarker,
                    participant.attack.dropsOnField,
                    participant.attack.incompletePasses,
                    participant.attack.callahanDrops,
                    participant.attack.discPossessions,
                    participant.attack.pulls,
                    participant.attack.bricks,
                    participant.defense.blocks,
                    participant.defense.blocksAsMarker,
                    participant.defense.blocksAsFieldPlayer,
                    participant.defense.interceptions,
                    participant.defense.callahans,
                    participant.time.possessionTimeMs,
                    participant.time.averagePossessionTimeMs,
                    participant.time.averagePossessionTimeAvailable,
                )
            }
        }
    }

    private fun StringBuilder.appendCsvRow(vararg values: Any?) {
        append(values.joinToString(",") { value -> value.toCsvValue() })
        append("\r\n")
    }

    private fun Any?.toCsvValue(): String {
        if (this == null) return ""
        val value = toString()
        return if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
