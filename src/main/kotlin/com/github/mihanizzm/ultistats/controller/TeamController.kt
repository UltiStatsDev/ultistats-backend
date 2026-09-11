package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.dto.common.PageResponse
import com.github.mihanizzm.ultistats.dto.common.SortParam
import com.github.mihanizzm.ultistats.dto.request.CreateTeamRequest
import com.github.mihanizzm.ultistats.dto.request.TeamFilterRequest
import com.github.mihanizzm.ultistats.dto.request.UpdateTeamRequest
import com.github.mihanizzm.ultistats.dto.request.UpsertTeamPlayerRequest
import com.github.mihanizzm.ultistats.dto.response.PhotoUrlResponse
import com.github.mihanizzm.ultistats.dto.response.PlayerTeamMembershipResponse
import com.github.mihanizzm.ultistats.dto.response.TeamDetailResponse
import com.github.mihanizzm.ultistats.dto.response.TeamListItemResponse
import com.github.mihanizzm.ultistats.dto.response.TeamPlayerResponse
import com.github.mihanizzm.ultistats.facade.TeamFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/v1/teams")
@Tag(name = "Teams", description = "Управление командами")
class TeamController(
    private val teamFacade: TeamFacade,
) {
    @GetMapping
    @Operation(summary = "Получить команды с пагинацией, фильтрацией и сортировкой")
    fun getAll(
        @Parameter(description = "Номер страницы (начиная с 0)")
        @RequestParam(defaultValue = "0")
        page: Int,

        @Parameter(description = "Размер страницы")
        @RequestParam(defaultValue = "20")
        size: Int,

        @Parameter(description = "Фильтр по названию (частичное совпадение)")
        @RequestParam(required = false)
        name: String?,

        @Parameter(
            description = "Сортировка. Формат: field:direction. " +
                "Доступные поля: name. По умолчанию: name:asc",
            example = "name:asc"
        )
        @RequestParam(required = false)
        sort: SortParam?,
    ): PageResponse<TeamListItemResponse> {
        val filter = TeamFilterRequest(name = name)
        return teamFacade.getAllPaged(page, size, filter, sort ?: TeamFacade.DEFAULT_SORT)
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить команду по ID")
    fun getById(@PathVariable id: UUID): ResponseEntity<TeamDetailResponse> =
        teamFacade.getById(id)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @PostMapping
    @Operation(summary = "Создать команду")
    fun create(@RequestBody request: CreateTeamRequest): ResponseEntity<TeamDetailResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(teamFacade.create(request))

    @PutMapping("/{id}")
    @Operation(summary = "Обновить команду (частичное обновление)")
    fun update(@PathVariable id: UUID, @RequestBody request: UpdateTeamRequest): ResponseEntity<TeamDetailResponse> =
        teamFacade.update(id, request)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить команду")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID): ResponseEntity<Unit> =
        if (teamFacade.delete(id)) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()

    @GetMapping("/{teamId}/players")
    @Operation(summary = "Получить состав команды")
    fun getPlayers(@PathVariable teamId: UUID): ResponseEntity<List<TeamPlayerResponse>> =
        teamFacade.getMemberships(teamId)?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @PutMapping("/{teamId}/players/{playerId}")
    @Operation(summary = "Создать или обновить членство игрока в команде")
    fun putPlayer(
        @PathVariable teamId: UUID,
        @PathVariable playerId: UUID,
        @RequestBody request: UpsertTeamPlayerRequest,
    ): ResponseEntity<PlayerTeamMembershipResponse> = try {
        teamFacade.putPlayer(teamId, playerId, request.number)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    } catch (_: DataIntegrityViolationException) {
        ResponseEntity.status(HttpStatus.CONFLICT).build()
    } catch (_: IllegalArgumentException) {
        ResponseEntity.badRequest().build()
    }

    @DeleteMapping("/{teamId}/players/{playerId}")
    @Operation(summary = "Убрать игрока из команды")
    fun removePlayer(
        @PathVariable teamId: UUID,
        @PathVariable playerId: UUID
    ): ResponseEntity<Unit> =
        if (teamFacade.removePlayer(teamId, playerId)) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()

    @PostMapping("/{teamId}/uploadPhoto", consumes =
    [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(summary = "Загрузить аватар для команды")
    fun uploadPhoto(
        @PathVariable teamId: UUID,
        @RequestBody multipartFile: MultipartFile,
    ): ResponseEntity<PhotoUrlResponse> =
        teamFacade.uploadPhoto(teamId, multipartFile)
            ?.let { ResponseEntity.status(HttpStatus.CREATED).body(it) }
            ?: ResponseEntity.notFound().build()

    @GetMapping("/{teamId}/photoUrl")
    @Operation(summary = "Получить URL изображения команды")
    fun getPhotoUrl(@PathVariable teamId: UUID): ResponseEntity<PhotoUrlResponse> =
        teamFacade.getPhotoUrl(teamId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @DeleteMapping("/{teamId}/photoUrl")
    @Operation(summary = "Удалить URL изображения команды")
    fun removePhotoUrl(@PathVariable teamId: UUID): ResponseEntity<PhotoUrlResponse> =
        teamFacade.deletePhotoUrl(teamId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
}
