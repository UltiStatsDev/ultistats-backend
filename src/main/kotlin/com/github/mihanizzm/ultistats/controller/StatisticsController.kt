package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.dto.response.statistics.MatchStatisticsResponse
import com.github.mihanizzm.ultistats.facade.StatisticsFacade
import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/matches/{matchId}/statistics")
@Tag(name = "Statistics", description = "Получение статистики матча")
class StatisticsController(
    private val statisticsFacade: StatisticsFacade,
) {
    @GetMapping
    @Operation(summary = "Получить статистику матча")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Статистика матча",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = MatchStatisticsResponse::class),
                    examples = [ExampleObject(
                        value = """{"matchId":"00000000-0000-0000-0000-000000000100","teams":[{"teamId":"00000000-0000-0000-0000-000000000001","teamName":"Flying Bears","attack":{"score":1,"breaks":1,"completePasses":1,"allPasses":1,"pulls":1,"bricks":0,"possessions":1},"defense":{"blocks":0,"blocksAsMarker":0,"blocksAsFieldPlayer":0,"interceptions":0,"callahans":0},"time":{"possessionTimeMs":42000,"betweenPointsTimeMs":7000,"timeoutTimeMs":0},"participants":[{"participantId":"00000000-0000-0000-0000-000000000010","kind":"PLAYER","unknownSlot":null,"firstName":"Ivan","lastName":"Ivanov","displayName":"Ivan Ivanov","number":17,"attack":{"passes":1,"catches":1,"assists":1,"goals":0,"dropsOnMarker":0,"dropsOnField":0,"incompletePasses":0,"callahanDrops":0,"discPossessions":1,"pulls":0,"bricks":0},"defense":{"blocks":0,"blocksAsMarker":0,"blocksAsFieldPlayer":0,"interceptions":0,"callahans":0},"time":{"possessionTimeMs":42000,"averagePossessionTimeMs":42000}}]}],"time":{"totalTimeMs":90000,"betweenPointsTimeMs":7000,"timeoutTimeMs":0,"halftimeTimeMs":0,"pureGameTimeMs":83000}}""",
                    )],
                )],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Матч не найден",
                content = [Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = Schema(implementation = ProblemDetail::class),
                )],
            ),
        ],
    )
    fun getStatistics(
        @PathVariable matchId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<*> = statisticsFacade.get(matchId)
        ?.let { ResponseEntity.ok(it) }
        ?: notFound(matchId, request)

    @GetMapping("/export", produces = [APPLICATION_ZIP_VALUE])
    @Operation(summary = "Экспортировать статистику матча в ZIP с CSV-таблицами")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "ZIP-архив со статистикой матча",
                content = [Content(
                    mediaType = APPLICATION_ZIP_VALUE,
                    schema = Schema(type = "string", format = "binary"),
                )],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Матч не найден",
                content = [Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = Schema(implementation = ProblemDetail::class),
                )],
            ),
        ],
    )
    fun exportStatistics(
        @PathVariable matchId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<*> = statisticsFacade.export(matchId)
        ?.let { archive ->
            ResponseEntity.ok()
                .contentType(APPLICATION_ZIP)
                .header(
                    HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment()
                        .filename("match_${matchId}_statistics.zip")
                        .build()
                        .toString(),
                )
                .body(archive)
        }
        ?: notFound(matchId, request)

    private fun notFound(matchId: UUID, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val problem = MatchProblem(
            code = MatchProblemCode.RESOURCE_NOT_FOUND,
            title = "Resource not found",
            detail = "Match $matchId not found",
        )
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problem.toProblemDetail(HttpStatus.NOT_FOUND, URI.create(request.requestURI)))
    }

    companion object {
        private const val APPLICATION_ZIP_VALUE = "application/zip"
        private val APPLICATION_ZIP = MediaType("application", "zip")
    }
}
