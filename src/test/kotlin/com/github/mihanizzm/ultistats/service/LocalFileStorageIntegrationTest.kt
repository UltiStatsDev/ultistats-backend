package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.model.Player
import com.github.mihanizzm.ultistats.model.Team
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.util.FileSystemUtils
import org.springframework.web.multipart.MultipartFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

@Suppress("NonAsciiCharacters")
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalFileStorageIntegrationTest {
    @Autowired
    private lateinit var storage: LocalFileStorageService

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var playerService: PlayerService

    @Autowired
    private lateinit var teamService: TeamService

    @BeforeEach
    fun prepareStorage() {
        teamService.getAll().forEach { teamService.delete(it.id) }
        playerService.getAll().forEach { playerService.delete(it.id) }
        FileSystemUtils.deleteRecursively(storageRoot)
        Files.createDirectories(storageRoot)
    }

    @AfterAll
    fun removeStorage() {
        FileSystemUtils.deleteRecursively(storageRoot)
    }

    @Test
    fun `подозрительное имя файла не попадает в путь хранения`() {
        val file = MockMultipartFile(
            "file",
            "../../photo.jpg",
            "image/png",
            PNG_BYTES,
        )

        val url = storage.upload(file)

        val storedFiles = Files.list(storageRoot).use { it.toList() }
        assertThat(storedFiles).hasSize(1)
        assertThat(storedFiles.single().fileName.toString())
            .matches("[0-9a-f-]{36}\\.png")
        assertThat(url).isEqualTo("/uploads/${storedFiles.single().fileName}")
        assertThat(Files.readAllBytes(storedFiles.single())).isEqualTo(PNG_BYTES)
    }

    @Test
    fun `jpeg png и webp получают расширение по сигнатуре`() {
        val files = listOf(
            MockMultipartFile("file", "wrong.bin", "image/jpeg", JPEG_BYTES) to "jpg",
            MockMultipartFile("file", "wrong.bin", "image/png", PNG_BYTES) to "png",
            MockMultipartFile("file", "wrong.bin", "image/webp", WEBP_BYTES) to "webp",
        )

        files.forEach { (file, extension) ->
            assertThat(storage.upload(file)).matches("/uploads/[0-9a-f-]{36}\\.$extension")
        }
    }

    @Test
    fun `пустой файл отклоняется до записи`() {
        assertThatThrownBy {
            storage.upload(MockMultipartFile("file", "empty.png", "image/png", byteArrayOf()))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("File must not be empty")

        assertThat(Files.list(storageRoot).use { it.toList() }).isEmpty()
    }

    @Test
    fun `файл больше 10 MiB отклоняется до записи`() {
        val oversized = ByteArray(MAX_FILE_SIZE_BYTES + 1)
        PNG_BYTES.copyInto(oversized)

        assertThatThrownBy {
            storage.upload(MockMultipartFile("file", "large.png", "image/png", oversized))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("File exceeds maximum size of 10 MiB")

        assertThat(Files.list(storageRoot).use { it.toList() }).isEmpty()
    }

    @Test
    fun `неподдерживаемая сигнатура отклоняется до записи`() {
        assertThatThrownBy {
            storage.upload(MockMultipartFile("file", "text.png", "image/png", "not-an-image".toByteArray()))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("File signature is not supported")

        assertThat(Files.list(storageRoot).use { it.toList() }).isEmpty()
    }

    @Test
    fun `content type должен совпадать с сигнатурой`() {
        assertThatThrownBy {
            storage.upload(MockMultipartFile("file", "photo.jpg", "image/jpeg", PNG_BYTES))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("File content does not match declared content type image/jpeg")

        assertThat(Files.list(storageRoot).use { it.toList() }).isEmpty()
    }

    @Test
    fun `ошибка чтения multipart потока возвращается как ошибка storage`() {
        val file = org.mockito.Mockito.mock(MultipartFile::class.java)
        org.mockito.Mockito.`when`(file.isEmpty).thenReturn(false)
        org.mockito.Mockito.`when`(file.size).thenReturn(PNG_BYTES.size.toLong())
        org.mockito.Mockito.`when`(file.inputStream).thenThrow(IOException("read failed"))

        assertThatThrownBy { storage.upload(file) }
            .isInstanceOf(FileStorageException::class.java)
            .hasMessage("Failed to read uploaded file")
            .hasCauseInstanceOf(IOException::class.java)

        assertThat(Files.list(storageRoot).use { it.toList() }).isEmpty()
    }

    @Test
    fun `удаление отклоняет URL вне управляемого storage namespace`() {
        listOf(
            "/outside/photo.png",
            "/uploads/nested/photo.png",
            "/uploads/..",
        ).forEach { url ->
            assertThatThrownBy { storage.delete(url) }
                .isInstanceOf(FileStorageException::class.java)
                .hasMessage("Storage URL is not managed by this service")
        }
    }

    @Test
    fun `endpoint игрока принимает multipart часть file`() {
        val player = Player(java.util.UUID.randomUUID(), "Photo", "Player")
        playerService.create(player)

        mockMvc.perform(
            multipart("/api/v1/players/${player.id}/uploadPhoto")
                .file(MockMultipartFile("file", "player.png", "image/png", PNG_BYTES)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.matchesPattern("/uploads/[0-9a-f-]{36}\\.png")))
    }

    @Test
    fun `endpoint команды принимает multipart часть file`() {
        val team = Team(java.util.UUID.randomUUID(), "Photo Team")
        teamService.create(team)

        mockMvc.perform(
            multipart("/api/v1/teams/${team.id}/uploadPhoto")
                .file(MockMultipartFile("file", "team.webp", "image/webp", WEBP_BYTES)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.matchesPattern("/uploads/[0-9a-f-]{36}\\.webp")))
    }

    @Test
    fun `невалидное изображение возвращает ProblemDetail`() {
        val player = Player(java.util.UUID.randomUUID(), "Photo", "Player")
        playerService.create(player)

        mockMvc.perform(
            multipart("/api/v1/players/${player.id}/uploadPhoto")
                .file(MockMultipartFile("file", "fake.png", "image/png", "not-an-image".toByteArray())),
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.code").value("INVALID_FILE_UPLOAD"))
            .andExpect(jsonPath("$.title").value("Invalid file upload"))
            .andExpect(jsonPath("$.detail").value("File signature is not supported"))
            .andExpect(jsonPath("$.instance").value("/api/v1/players/${player.id}/uploadPhoto"))
    }

    @Test
    fun `OpenAPI документирует photo resource и deprecated aliases`() {
        val playerPhoto = "$.paths['/api/v1/players/{playerId}/photo']"
        val teamPhoto = "$.paths['/api/v1/teams/{teamId}/photo']"
        val legacyPlayerUpload = "$.paths['/api/v1/players/{playerId}/uploadPhoto'].post"
        val legacyPlayerUrl = "$.paths['/api/v1/players/{playerId}/photoUrl']"
        val legacyTeamUpload = "$.paths['/api/v1/teams/{teamId}/uploadPhoto'].post"
        val legacyTeamUrl = "$.paths['/api/v1/teams/{teamId}/photoUrl']"

        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$playerPhoto.put.requestBody.content['multipart/form-data'].schema.required[0]").value("file"))
            .andExpect(jsonPath("$playerPhoto.put.requestBody.content['multipart/form-data'].schema.properties.file.format").value("binary"))
            .andExpect(
                jsonPath("$playerPhoto.put.responses['400'].content['application/problem+json'].schema['\$ref']")
                    .value("#/components/schemas/ProblemDetail"),
            )
            .andExpect(
                jsonPath("$playerPhoto.put.responses['503'].content['application/problem+json'].schema['\$ref']")
                    .value("#/components/schemas/ProblemDetail"),
            )
            .andExpect(
                jsonPath("$playerPhoto.delete.responses['503'].content['application/problem+json'].schema['\$ref']")
                    .value("#/components/schemas/ProblemDetail"),
            )
            .andExpect(jsonPath("$teamPhoto.put.requestBody.content['multipart/form-data'].schema.required[0]").value("file"))
            .andExpect(jsonPath("$teamPhoto.put.requestBody.content['multipart/form-data'].schema.properties.file.format").value("binary"))
            .andExpect(jsonPath("$legacyPlayerUpload.deprecated").value(true))
            .andExpect(jsonPath("$legacyPlayerUrl.get.deprecated").value(true))
            .andExpect(jsonPath("$legacyPlayerUrl.delete.deprecated").value(true))
            .andExpect(jsonPath("$legacyTeamUpload.deprecated").value(true))
            .andExpect(jsonPath("$legacyTeamUrl.get.deprecated").value(true))
            .andExpect(jsonPath("$legacyTeamUrl.delete.deprecated").value(true))
    }

    @Test
    fun `статический ресурс читается из настроенной директории`() {
        Files.writeString(storageRoot.resolve("served-photo.txt"), "served-content")

        mockMvc.perform(get("/uploads/served-photo.txt"))
            .andExpect(status().isOk)
            .andExpect(content().string("served-content"))
    }

    companion object {
        private const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024
        private val JPEG_BYTES = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        )
        private val PNG_BYTES = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        private val WEBP_BYTES = byteArrayOf(
            0x52, 0x49, 0x46, 0x46,
            0x00, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50,
        )
        private val storageRoot: Path = Files.createTempDirectory("ultistats-storage-test-")

        @JvmStatic
        @DynamicPropertySource
        fun storageProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.storage.root") { storageRoot.toString() }
        }
    }
}
