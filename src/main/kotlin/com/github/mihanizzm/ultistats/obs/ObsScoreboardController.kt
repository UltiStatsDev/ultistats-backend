package com.github.mihanizzm.ultistats.obs

import com.github.mihanizzm.ultistats.exception.EntityNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/obs/scoreboard/{matchId}")
class ObsScoreboardController(private val service: ObsScoreboardService) {
    @ExceptionHandler(EntityNotFoundException::class)
    fun missing(cause: EntityNotFoundException) = ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, cause.message))

    @GetMapping
    @Operation(summary = "Каноническое табло OBS", description = "Read-only committed score, actual start/end and serverNow clock anchor, ordered team snapshots and per-match profile. Missing/deleted match 404. GET never creates a profile.")
    fun get(@PathVariable matchId: UUID) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.snapshot(matchId))

    @PutMapping("/profile")
    @Operation(summary = "Заменить эфирный профиль табло", description = "Per-match only; last save wins. shortNames: up to 80 nonblank characters, teamColors: #RRGGBB, keys must belong to this match; swapped moves whole teams. {} resets. AFTER_COMMIT scoreboard-profile-changed on shared OBS stream. No game/team/EventLog mutations. Trusted network, no authentication.")
    fun put(@PathVariable matchId: UUID, @Valid @RequestBody profile: ObsScoreboardProfile) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.save(matchId, profile))
}
