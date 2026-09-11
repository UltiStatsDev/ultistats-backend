package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.dto.common.PageResponse
import com.github.mihanizzm.ultistats.dto.common.SortParam
import com.github.mihanizzm.ultistats.dto.request.CreatePlayerRequest
import com.github.mihanizzm.ultistats.dto.request.PlayerFilterRequest
import com.github.mihanizzm.ultistats.dto.request.UpdatePlayerRequest
import com.github.mihanizzm.ultistats.dto.response.PhotoUrlResponse
import com.github.mihanizzm.ultistats.dto.response.PlayerDetailResponse
import com.github.mihanizzm.ultistats.dto.response.PlayerListItemResponse
import com.github.mihanizzm.ultistats.dto.response.PlayerTeamMembershipResponse
import com.github.mihanizzm.ultistats.facade.PlayerFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/v1/players")
@Tag(name = "Players", description = "Управление игроками")
class PlayerController(
    private val playerFacade: PlayerFacade,
) {
    @GetMapping
    @Operation(summary = "Получить игроков с пагинацией, фильтрацией и сортировкой")
    fun getAll(
        @Parameter(description = "Номер страницы (начиная с 0)")
        @RequestParam(defaultValue = "0")
        page: Int,

        @Parameter(description = "Размер страницы")
        @RequestParam(defaultValue = "20")
        size: Int,

        @Parameter(description = "Фильтр по имени (частичное совпадение)")
        @RequestParam(required = false)
        name: String?,

        @Parameter(description = "Фильтр по ID команды")
        @RequestParam(required = false)
        teamId: UUID?,

        @Parameter(
            description = "Сортировка. Формат: field:direction. " +
                "Доступные поля: lastName, firstName, number, teamId. " +
                "По умолчанию: lastName:asc",
            example = "lastName:asc"
        )
        @RequestParam(required = false)
        sort: SortParam?,
    ): PageResponse<PlayerListItemResponse> {
        val filter = PlayerFilterRequest(name = name, teamId = teamId)
        return playerFacade.getAllPaged(page, size, filter, sort ?: PlayerFacade.DEFAULT_SORT)
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить игрока по ID")
    fun getById(@PathVariable id: UUID): ResponseEntity<PlayerDetailResponse> =
        playerFacade.getById(id)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @GetMapping("/{playerId}/teams")
    @Operation(summary = "Получить членства игрока в командах")
    fun getTeams(@PathVariable playerId: UUID): ResponseEntity<List<PlayerTeamMembershipResponse>> =
        playerFacade.getMemberships(playerId)?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @PostMapping
    @Operation(summary = "Создать игрока")
    fun create(@RequestBody request: CreatePlayerRequest): ResponseEntity<PlayerDetailResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(playerFacade.create(request))

    @PutMapping("/{id}")
    @Operation(summary = "Обновить игрока (частичное обновление)")
    fun update(@PathVariable id: UUID, @RequestBody request: UpdatePlayerRequest): ResponseEntity<PlayerDetailResponse> =
        playerFacade.update(id, request)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить игрока")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID): ResponseEntity<Unit> =
        if (playerFacade.delete(id)) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()

    @PostMapping("/{playerId}/uploadPhoto", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(summary = "Загрузить аватар для игрока")
    fun uploadPhoto(
        @PathVariable playerId: UUID,
        @RequestBody multipartFile: MultipartFile,
    ): ResponseEntity<PhotoUrlResponse> =
        playerFacade.uploadPhoto(playerId, multipartFile)
            ?.let { ResponseEntity.status(HttpStatus.CREATED).body(it) }
            ?: ResponseEntity.notFound().build()

    @GetMapping("/{playerId}/photoUrl")
    @Operation(summary = "Получить URL изображения игрока")
    fun getPhotoUrl(@PathVariable playerId: UUID): ResponseEntity<PhotoUrlResponse> =
        playerFacade.getPhotoUrl(playerId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @DeleteMapping("/{playerId}/photoUrl")
    @Operation(summary = "Удалить URL изображения игрока")
    fun removePhotoUrl(@PathVariable playerId: UUID): ResponseEntity<PhotoUrlResponse> =
        playerFacade.deletePhotoUrl(playerId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
}
