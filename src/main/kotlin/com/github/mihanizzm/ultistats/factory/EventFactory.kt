package com.github.mihanizzm.ultistats.factory

import com.github.mihanizzm.ultistats.dto.request.CreateEventRequest
import com.github.mihanizzm.ultistats.dto.request.OnePlayerEventRequest
import com.github.mihanizzm.ultistats.dto.request.SystemEventRequest
import com.github.mihanizzm.ultistats.dto.request.TeamEventRequest
import com.github.mihanizzm.ultistats.dto.request.TwoPlayerEventRequest
import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.EventCategory
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.SystemEvent
import com.github.mihanizzm.ultistats.model.events.TeamEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchParticipantRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchTeamRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class EventFactory(
    private val matchParticipantRepository: SpringDataMatchParticipantRepository,
    private val matchTeamRepository: SpringDataMatchTeamRepository,
) {
    fun createFromRequest(request: CreateEventRequest, matchId: UUID): Event? =
        when (val result = createValidatedFromRequest(request, matchId)) {
            is EventCreationResult.Success -> result.event
            is EventCreationResult.Invalid -> null
        }

    fun createValidatedFromRequest(request: CreateEventRequest, matchId: UUID): EventCreationResult {
        val teamByParticipantId = matchParticipantRepository.findAllByMatchId(matchId)
            .associate { it.participantId to it.teamId }
        return when (request) {
            is OnePlayerEventRequest -> {
                if (request.type.category != EventCategory.ONE_PLAYER) {
                    return invalid("Event type ${request.type} is not valid for a one-player request")
                }
                if (request.participantId !in teamByParticipantId) {
                    return invalid("Participant ${request.participantId} is not part of match $matchId")
                }
                EventCreationResult.Success(OnePlayerEvent(request.participantId, request.occurredAt, request.type))
            }
            is TwoPlayerEventRequest -> {
                if (request.type.category != EventCategory.TWO_PLAYER) {
                    return invalid("Event type ${request.type} is not valid for a two-player request")
                }
                if (request.fromParticipantId == request.toParticipantId) {
                    return invalid("Event participants must be different")
                }
                val fromTeam = teamByParticipantId[request.fromParticipantId]
                    ?: return invalid("Participant ${request.fromParticipantId} is not part of match $matchId")
                val toTeam = teamByParticipantId[request.toParticipantId]
                    ?: return invalid("Participant ${request.toParticipantId} is not part of match $matchId")
                val sameTeamRequired = request.type == EventType.PASS || request.type == EventType.GOAL
                if (sameTeamRequired != (fromTeam == toTeam)) {
                    val relation = if (sameTeamRequired) "the same match team" else "opposing match teams"
                    return invalid("${request.type} requires participants from $relation")
                }
                EventCreationResult.Success(
                    TwoPlayerEvent(
                        request.fromParticipantId,
                        request.toParticipantId,
                        request.occurredAt,
                        request.type,
                    ),
                )
            }
            is TeamEventRequest -> {
                if (request.type.category != EventCategory.TEAM) {
                    return invalid("Event type ${request.type} is not valid for a team request")
                }
                val teamExists = matchTeamRepository.findAllByMatchIdOrderByPosition(matchId)
                    .any { it.teamId == request.teamId }
                if (!teamExists) return invalid("Team ${request.teamId} is not part of match $matchId")
                EventCreationResult.Success(TeamEvent(request.teamId, request.occurredAt, request.type))
            }
            is SystemEventRequest -> {
                if (request.type.category != EventCategory.SYSTEM) {
                    return invalid("Event type ${request.type} is not valid for a system request")
                }
                EventCreationResult.Success(SystemEvent(request.occurredAt, request.type))
            }
        }
    }

    private fun invalid(detail: String) = EventCreationResult.Invalid(detail)
}
