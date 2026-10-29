package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.model.Player
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataPlayerRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataTeamRepository
import com.github.mihanizzm.ultistats.service.FileStorageException
import com.github.mihanizzm.ultistats.service.FileStorageService
import com.github.mihanizzm.ultistats.service.PlayerService
import jakarta.servlet.ServletException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.util.FileSystemUtils
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("NonAsciiCharacters")
@SpringBootTest
@AutoConfigureMockMvc
@Import(PhotoResourceTestConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhotoResourceIntegrationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var scenarioFactory: PhotoResourceScenarioFactory

    @MockitoSpyBean
    lateinit var fileStorageService: FileStorageService

    @MockitoSpyBean
    lateinit var playerService: PlayerService

    @BeforeEach
    fun prepareStorage() {
        FileSystemUtils.deleteRecursively(storageRoot)
        Files.createDirectories(storageRoot)
    }

    @AfterAll
    fun removeStorage() {
        FileSystemUtils.deleteRecursively(storageRoot)
    }

    @Test
    fun `PUT и GET photo resource игрока возвращают сохраненный URL`() {
        val player = scenarioFactory.createPlayer()

        val url = putPhoto("/api/v1/players/${player.id}/photo", "image/png", PNG_BYTES)
            .responseUrl()

        assertThat(url).matches("/uploads/[0-9a-f-]{36}\\.png")
        assertThat(Files.readAllBytes(storedPath(url))).isEqualTo(PNG_BYTES)
        mockMvc.perform(get("/api/v1/players/${player.id}/photo"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.url").value(url))
    }

    @Test
    fun `PUT photo resource игрока принимает legacy часть multipartFile`() {
        val player = scenarioFactory.createPlayer()

        mockMvc.perform(
            photoPutRequest(
                url = "/api/v1/players/${player.id}/photo",
                contentType = "image/png",
                content = PNG_BYTES,
                partName = "multipartFile",
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.url").exists())
    }

    @Test
    fun `PUT photo resource команды принимает legacy часть multipartFile`() {
        val team = scenarioFactory.createTeam()

        mockMvc.perform(
            photoPutRequest(
                url = "/api/v1/teams/${team.id}/photo",
                contentType = "image/webp",
                content = WEBP_BYTES,
                partName = "multipartFile",
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.url").exists())
    }

    @Test
    fun `deprecated photo uploads принимают legacy часть multipartFile`() {
        val player = scenarioFactory.createPlayer()
        val team = scenarioFactory.createTeam()

        mockMvc.perform(
            photoPostRequest(
                url = "/api/v1/players/${player.id}/uploadPhoto",
                contentType = "image/png",
                content = PNG_BYTES,
                partName = "multipartFile",
            ),
        ).andExpect(status().isCreated)

        mockMvc.perform(
            photoPostRequest(
                url = "/api/v1/teams/${team.id}/uploadPhoto",
                contentType = "image/webp",
                content = WEBP_BYTES,
                partName = "multipartFile",
            ),
        ).andExpect(status().isCreated)
    }

    @Test
    fun `photo upload отклоняет одновременно file и multipartFile`() {
        val player = scenarioFactory.createPlayer()

        mockMvc.perform(
            multipart("/api/v1/players/${player.id}/photo")
                .file(MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES))
                .file(MockMultipartFile("multipartFile", "legacy.png", "image/png", PNG_BYTES))
                .with { request -> request.apply { method = "PUT" } },
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_FILE_UPLOAD"))
    }

    @Test
    fun `замена photo resource игрока удаляет старый файл`() {
        val player = scenarioFactory.createPlayer()
        val oldUrl = putPhoto("/api/v1/players/${player.id}/photo", "image/png", PNG_BYTES)
            .responseUrl()

        val newUrl = putPhoto("/api/v1/players/${player.id}/photo", "image/jpeg", JPEG_BYTES)
            .responseUrl()

        assertThat(newUrl).isNotEqualTo(oldUrl)
        assertThat(storedPath(oldUrl)).doesNotExist()
        assertThat(Files.readAllBytes(storedPath(newUrl))).isEqualTo(JPEG_BYTES)
        assertThat(Files.list(storageRoot).use { it.toList() }).hasSize(1)
    }

    @Test
    fun `DELETE photo resource игрока удаляет файл и сам ресурс`() {
        val player = scenarioFactory.createPlayer()
        val url = putPhoto("/api/v1/players/${player.id}/photo", "image/png", PNG_BYTES)
            .responseUrl()

        mockMvc.perform(delete("/api/v1/players/${player.id}/photo"))
            .andExpect(status().isNoContent)

        assertThat(storedPath(url)).doesNotExist()
        mockMvc.perform(get("/api/v1/players/${player.id}/photo"))
            .andExpect(status().isNotFound)
        mockMvc.perform(delete("/api/v1/players/${player.id}/photo"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `photo resource команды поддерживает полный lifecycle`() {
        val team = scenarioFactory.createTeam()
        val url = putPhoto("/api/v1/teams/${team.id}/photo", "image/webp", WEBP_BYTES)
            .responseUrl()

        mockMvc.perform(get("/api/v1/teams/${team.id}/photo"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.url").value(url))
        mockMvc.perform(delete("/api/v1/teams/${team.id}/photo"))
            .andExpect(status().isNoContent)

        assertThat(storedPath(url)).doesNotExist()
        mockMvc.perform(get("/api/v1/teams/${team.id}/photo"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `ошибка удаления старого файла при замене восстанавливает прежний photo resource`() {
        val player = scenarioFactory.createPlayer()
        val oldUrl = putPhoto("/api/v1/players/${player.id}/photo", "image/png", PNG_BYTES)
            .responseUrl()
        doThrow(FileStorageException("delete failed"))
            .`when`(fileStorageService)
            .delete(oldUrl)
        doThrow(IllegalStateException("restore failed"))
            .`when`(playerService)
            .update(matchingPlayer { it.id == player.id && it.photoUrl == oldUrl })

        mockMvc.perform(photoPutRequest("/api/v1/players/${player.id}/photo", "image/jpeg", JPEG_BYTES))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("FILE_STORAGE_ERROR"))

        mockMvc.perform(get("/api/v1/players/${player.id}/photo"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.url").value(oldUrl))
        assertThat(Files.list(storageRoot).use { it.toList() })
            .containsExactly(storedPath(oldUrl))
    }

    @Test
    fun `ошибка удаления файла восстанавливает удаленный photo resource`() {
        val player = scenarioFactory.createPlayer()
        val oldUrl = putPhoto("/api/v1/players/${player.id}/photo", "image/png", PNG_BYTES)
            .responseUrl()
        doThrow(FileStorageException("delete failed"))
            .`when`(fileStorageService)
            .delete(oldUrl)
        doThrow(IllegalStateException("restore failed"))
            .`when`(playerService)
            .update(matchingPlayer { it.id == player.id && it.photoUrl == oldUrl })

        mockMvc.perform(delete("/api/v1/players/${player.id}/photo"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("FILE_STORAGE_ERROR"))

        mockMvc.perform(get("/api/v1/players/${player.id}/photo"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.url").value(oldUrl))
        assertThat(storedPath(oldUrl)).exists()
    }

    @Test
    fun `параллельные замены photo resource не оставляют потерянный storage object`() {
        val player = scenarioFactory.createPlayer()
        putPhoto("/api/v1/players/${player.id}/photo", "image/png", PNG_BYTES)

        val bothRequestsReadOldState = CountDownLatch(2)
        Mockito.doAnswer { invocation ->
            val result = invocation.callRealMethod()
            if (Thread.currentThread().name.startsWith("pool-")) {
                bothRequestsReadOldState.countDown()
                check(bothRequestsReadOldState.await(5, TimeUnit.SECONDS))
            }
            result
        }.`when`(playerService).get(player.id)

        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<MvcResult> {
                putPhoto("/api/v1/players/${player.id}/photo", "image/jpeg", JPEG_BYTES)
            }
            val second = executor.submit<MvcResult> {
                putPhoto("/api/v1/players/${player.id}/photo", "image/webp", WEBP_BYTES)
            }

            val returnedUrls = setOf(
                first.get(10, TimeUnit.SECONDS).responseUrl(),
                second.get(10, TimeUnit.SECONDS).responseUrl(),
            )
            val currentUrl = mockMvc.perform(get("/api/v1/players/${player.id}/photo"))
                .andExpect(status().isOk)
                .andReturn()
                .responseUrl()

            assertThat(returnedUrls).contains(currentUrl)
            assertThat(Files.list(storageRoot).use { it.toList() })
                .containsExactly(storedPath(currentUrl))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `ошибка сохранения URL удаляет новый storage object`() {
        val player = scenarioFactory.createPlayer()
        doThrow(IllegalStateException("database update failed"))
            .`when`(playerService)
            .update(matchingPlayer { it.id == player.id && it.photoUrl != null })

        assertThrows<ServletException> {
            mockMvc.perform(photoPutRequest("/api/v1/players/${player.id}/photo", "image/png", PNG_BYTES))
        }

        assertThat(Files.list(storageRoot).use { it.toList() }).isEmpty()
        mockMvc.perform(get("/api/v1/players/${player.id}/photo"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `ошибка загрузки в storage возвращает отдельный ProblemDetail`() {
        val player = scenarioFactory.createPlayer()
        doThrow(FileStorageException("upload failed"))
            .`when`(fileStorageService)
            .upload(anyMultipartFile())

        mockMvc.perform(photoPutRequest("/api/v1/players/${player.id}/photo", "image/png", PNG_BYTES))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.status").value(503))
            .andExpect(jsonPath("$.title").value("File storage unavailable"))
            .andExpect(jsonPath("$.code").value("FILE_STORAGE_ERROR"))
            .andExpect(jsonPath("$.detail").value("File storage operation failed"))
            .andExpect(jsonPath("$.instance").value("/api/v1/players/${player.id}/photo"))
    }

    private fun putPhoto(url: String, contentType: String, content: ByteArray): MvcResult =
        mockMvc.perform(photoPutRequest(url, contentType, content))
            .andExpect(status().isOk)
            .andReturn()

    private fun photoPutRequest(
        url: String,
        contentType: String,
        content: ByteArray,
        partName: String = "file",
    ): MockHttpServletRequestBuilder =
        multipart(url)
            .file(MockMultipartFile(partName, "photo", contentType, content))
            .with { request -> request.apply { method = "PUT" } }

    private fun photoPostRequest(
        url: String,
        contentType: String,
        content: ByteArray,
        partName: String,
    ): MockHttpServletRequestBuilder =
        multipart(url)
            .file(MockMultipartFile(partName, "photo", contentType, content))

    private fun MvcResult.responseUrl(): String =
        objectMapper.readTree(response.contentAsString).get("url").asText()

    private fun storedPath(url: String): Path = storageRoot.resolve(url.substringAfterLast('/'))

    private fun matchingPlayer(predicate: (Player) -> Boolean): Player =
        Mockito.argThat(predicate) ?: Player(UUID(0, 0), "", "")

    private fun anyMultipartFile(): MultipartFile =
        Mockito.any(MultipartFile::class.java) ?: MockMultipartFile("file", byteArrayOf())

    companion object {
        private val PNG_BYTES = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        private val JPEG_BYTES = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        )
        private val WEBP_BYTES = byteArrayOf(
            0x52, 0x49, 0x46, 0x46,
            0x00, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50,
        )
        private val storageRoot: Path = Files.createTempDirectory("ultistats-photo-resource-test-")

        @JvmStatic
        @DynamicPropertySource
        fun storageProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.storage.root") { storageRoot.toString() }
        }
    }
}

class PhotoResourceScenarioFactory(
    private val playerRepository: SpringDataPlayerRepository,
    private val teamRepository: SpringDataTeamRepository,
) {
    fun createPlayer(): Player =
        playerRepository.save(Player(UUID.randomUUID(), "Photo", "Player"))

    fun createTeam(): Team =
        teamRepository.save(Team(UUID.randomUUID(), "Photo Team"))
}

@TestConfiguration
class PhotoResourceTestConfiguration {
    @Bean
    fun photoResourceScenarioFactory(
        playerRepository: SpringDataPlayerRepository,
        teamRepository: SpringDataTeamRepository,
    ) = PhotoResourceScenarioFactory(playerRepository, teamRepository)
}
