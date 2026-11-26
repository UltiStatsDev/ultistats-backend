package com.github.mihanizzm.ultistats.transfer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.mihanizzm.ultistats.dto.request.ApprovedPointCompletion
import com.github.mihanizzm.ultistats.dto.request.PointCompletionActionType.*
import com.github.mihanizzm.ultistats.dto.request.PointCompletionInsertion
import com.github.mihanizzm.ultistats.dto.response.ProjectedEvent
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.PointTimeline
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.PointCompletionHistoryService
import com.github.mihanizzm.ultistats.service.PointLineupClient.POINT_RESULTS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.*

@SpringBootTest(properties = [
    "ultistats.point-results.write-enabled=false",
    "ultistats.point-lineups.write-enabled=false",
    "ultistats.point-substitutions.write-enabled=false",
    "ultistats.point-incoming-reserves.write-enabled=false",
])
@ActiveProfiles("postgres-list")
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class ApprovedCompletionTransferPostgresTest {
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transfer: MatchTransferService
    @Autowired private lateinit var history: PointCompletionHistoryService
    @Autowired private lateinit var matches: MatchService

    @Test
    fun `isolated recovery remaps a later point and slots with all writers off`() {
        val packet = packageWithApproval()
        val source = packet.matches.single()
        val sourceApproval = source.approvedCompletions!!.single()
        val result = transfer.recoverApprovedPackage(packet)
        val copyId = result.matchIds.getValue(source.id)
        val copy = transfer.export(listOf(copyId), POINT_RESULTS, 8).matches.single()
        val copyApproval = copy.approvedCompletions!!.single()
        val canonical = PointTimeline.from(copyId, MatchStatus.FINISHED, copy.events.map {
            StoredEvent(it.id, it.sequenceNumber, it.toDomain())
        })
        assertNotEquals(sourceApproval.pointId, copyApproval.pointId)
        assertEquals(canonical.last().pointId, copyApproval.pointId)
        assertEquals(result.eventIds.getValue(source.events.first().id), canonical.last().openingEventId)
        assertEquals(result.eventIds.getValue(sourceApproval.closingEventId), copyApproval.closingEventId)
        assertEquals(source.events.map { Triple(it.sequenceNumber, it.type, it.occurredAt) },
            copy.events.map { Triple(it.sequenceNumber, it.type, it.occurredAt) })
        assertEquals(source.endedAt, copy.endedAt)
        assertEquals(source.startedAt, copy.startedAt)
        assertEquals(source.events.size, result.eventIds.size)
        assertTrue(source.events.map { it.id }.intersect(copy.events.map { it.id }.toSet()).isEmpty())

        val lineup = copy.pointLineups.single()
        val starter = lineup.teams.first().entries.single() as PortablePointLineupTemporary
        val reserve = lineup.substitutionSlots!!.single()
        val actions = copyApproval.insertions
        val originalActions = sourceApproval.insertions
        assertEquals(result.playerIds.values.single(), starter.resolvedParticipantId)
        assertNull(reserve.resolvedParticipantId)
        assertEquals(copy.events.last { it.type == EventType.PLAYER_SUBSTITUTION }.id, reserve.introducedByEventId)
        assertEquals(source.pointLineups.single().substitutionSlots!!.single().number, reserve.number)
        assertNotEquals(originalActions[0].fromActorId, starter.slotId)
        assertNotEquals(originalActions[2].fromActorId, reserve.slotId)
        assertEquals(listOf(starter.slotId, reserve.slotId, reserve.slotId), actions.map { it.fromActorId })
        assertEquals(result.participantIds.getValue(source.id).getValue(originalActions.last().toActorId!!),
            actions.last().toActorId)
        assertEquals(listOf(null, reserve.introducedByEventId, reserve.introducedByEventId), actions.map { it.afterEventId })
        assertEquals(listOf(0L, null, 5000L), actions.map { it.elapsedOffsetMs })
        assertTrue(originalActions.map { it.id }.intersect(actions.map { it.id }.toSet()).isEmpty())
        val projected = history.read(copyId, POINT_RESULTS)!!
        assertEquals(actions, projected.entries.filterIsInstance<ProjectedEvent.Restored>().map { it.insertion })
        assertEquals(copy.events.map { it.id }, projected.entries.filterIsInstance<ProjectedEvent.Original>().map { it.event.id })
        assertEquals(listOf(1, 1), matches.getOrThrow(copyId).teamScores.map { it.score })
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM match_point_completion_drafts WHERE match_id = ?", Int::class.java, copyId,
        ))
    }

    @Test
    fun `invalid and failed recovery leave no rows`() {
        val packet = packageWithApproval()
        val source = packet.matches.single()
        val approval = source.approvedCompletions!!.single()
        val actions = approval.insertions
        val before = rowCounts()
        val opposite = source.teams.last().participants.first().participantId
        for (invalid in listOf(
            approval.copy(closingEventId = source.events.first().id),
            approval.copy(pointId = UUID.randomUUID()),
            approval.copy(insertions = actions.dropLast(1) + actions.last().copy(type = CALLAHAN, toActorId = opposite)),
            approval.copy(insertions = actions.dropLast(1) + actions.last().copy(afterEventId = source.events.first().id)),
            approval.copy(insertions = actions.dropLast(1) + actions.last().copy(fromActorId = UUID.randomUUID())),
            approval.copy(insertions = actions.dropLast(1) + actions.last().copy(id = source.events.first().id)),
        )) {
            assertFailsWith<InvalidMatchTransferException> {
                transfer.recoverApprovedPackage(packet.copy(matches = listOf(source.copy(approvedCompletions = listOf(invalid)))))
            }
            assertEquals(before, rowCounts())
        }
        jdbc.execute("""ALTER TABLE match_point_completion_approvals ADD CONSTRAINT fixture_292_approval
            CHECK (FALSE) NOT VALID""")
        try {
            assertFailsWith<DataIntegrityViolationException> { transfer.recoverApprovedPackage(packet) }
            assertEquals(before, rowCounts())
        } finally {
            jdbc.execute("ALTER TABLE match_point_completion_approvals DROP CONSTRAINT fixture_292_approval")
        }
    }

    private fun rowCounts() = listOf("matches", "teams", "players", "match_teams", "match_participants", "events",
        "match_point_lineups", "match_point_lineup_entries", "match_point_completion_approvals").map {
        jdbc.queryForObject("SELECT count(*) FROM $it", Long::class.java)
    }

    private fun packageWithApproval(): MatchTransferPackage {
        val base = json.readValue<MatchTransferPackage>(MatchTransferExamples.INCOMING_RESERVE_PACKAGE)
        val source = base.matches.single()
        val start = source.startedAt!!
        val (home, away) = source.teams
        val first = PortableEvent(UUID.randomUUID(), 1, EventType.POINT_RESULT, start, teamId = away.teamId)
        val last = PortableEvent(UUID.randomUUID(), 7, EventType.POINT_RESULT, start.plusSeconds(6), teamId = home.teamId)
        val lineup = source.pointLineups.single()
        val startingTeam = lineup.teams.first()
        val temporary = startingTeam.entries.single() as PortablePointLineupTemporary
        val identified = temporary.copy(resolvedParticipantId = base.players.single().id)
        val timeline = listOf(first) + source.events.map { it.copy(sequenceNumber = it.sequenceNumber + 2) } + last
        val point = PointTimeline.from(source.id, MatchStatus.FINISHED,
            timeline.map { StoredEvent(it.id, it.sequenceNumber, it.toDomain()) }).last()
        val reserve = lineup.substitutionSlots!!.single()
        val actions = listOf(
            PointCompletionInsertion(UUID.randomUUID(), null, POSSESSION_SET, temporary.slotId, null, 0),
            PointCompletionInsertion(UUID.randomUUID(), reserve.introducedByEventId, POSSESSION_SET, reserve.slotId, null, null),
            PointCompletionInsertion(UUID.randomUUID(), reserve.introducedByEventId, GOAL, reserve.slotId,
                home.participants.first { it.unknownSlot == 1 }.participantId, 5000),
        )
        return base.copy(formatVersion = 8, matches = listOf(source.copy(
            endedAt = start.plusSeconds(7),
            events = timeline,
            pointLineups = listOf(lineup.copy(openingEventId = first.id,
                teams = listOf(startingTeam.copy(entries = listOf(identified)), lineup.teams.last()))),
            approvedCompletions = listOf(ApprovedPointCompletion(point.pointId, last.id, actions)),
        )))
    }
}
