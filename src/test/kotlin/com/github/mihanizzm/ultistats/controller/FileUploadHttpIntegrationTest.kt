package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.util.FileSystemUtils
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.servlet.multipart.max-file-size=11MB",
        "spring.servlet.multipart.max-request-size=12MB",
        "server.tomcat.max-http-form-post-size=12MB",
        "server.tomcat.max-swallow-size=12MB",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileUploadHttpIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @LocalServerPort
    private var port: Int = 0

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
    fun `файл ровно 10 MiB проходит multipart transport`() {
        val playerId = createPlayer()
        val content = ByteArray(MAX_FILE_SIZE_BYTES)
        PNG_SIGNATURE.copyInto(content)

        val response = upload(playerId, content)

        assertThat(response.status)
            .withFailMessage("Unexpected response: %s", response.body)
            .isEqualTo(HttpStatus.CREATED.value())
        assertThat(objectMapper.readTree(response.body).get("url").asText())
            .matches("/uploads/[0-9a-f-]{36}\\.png")
    }

    @Test
    fun `файл больше 10 MiB получает INVALID_FILE_UPLOAD через multipart transport`() {
        val playerId = createPlayer()
        val content = ByteArray(MAX_FILE_SIZE_BYTES + 1)
        PNG_SIGNATURE.copyInto(content)

        val response = upload(playerId, content)

        assertThat(response.status).isEqualTo(HttpStatus.BAD_REQUEST.value())
        val problem = objectMapper.readTree(response.body)
        assertThat(problem.get("status").asInt()).isEqualTo(400)
        assertThat(problem.get("code").asText()).isEqualTo("INVALID_FILE_UPLOAD")
        assertThat(problem.get("title").asText()).isEqualTo("Invalid file upload")
        assertThat(problem.get("detail").asText()).isEqualTo("File exceeds maximum size of 10 MiB")
    }

    @Test
    fun `превышение servlet multipart лимита получает INVALID_FILE_UPLOAD`() {
        val playerId = createPlayer()
        val content = ByteArray(TRANSPORT_MAX_FILE_SIZE_BYTES + 1)
        PNG_SIGNATURE.copyInto(content)

        val response = upload(playerId, content)

        assertThat(response.status).isEqualTo(HttpStatus.BAD_REQUEST.value())
        val problem = objectMapper.readTree(response.body)
        assertThat(problem.get("code").asText()).isEqualTo("INVALID_FILE_UPLOAD")
        assertThat(problem.get("detail").asText()).isEqualTo("File exceeds maximum size of 10 MiB")
    }

    private fun createPlayer(): String {
        val response = restTemplate.postForEntity(
            "/api/v1/players",
            mapOf("firstName" to "Multipart", "lastName" to "Boundary"),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return objectMapper.readTree(response.body).get("id").asText()
    }

    private fun upload(playerId: String, content: ByteArray): UploadResponse {
        val boundary = "ultistats-upload-boundary"
        val prefix = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"photo.png\"\r\n" +
                "Content-Type: image/png\r\n" +
                "Content-Length: ${content.size}\r\n\r\n"
            ).toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        val body = ByteArray(prefix.size + content.size + suffix.size)
        prefix.copyInto(body)
        content.copyInto(body, prefix.size)
        suffix.copyInto(body, prefix.size + content.size)

        val connection = URI("http://localhost:$port/api/v1/players/$playerId/uploadPhoto")
            .toURL()
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }

        val status = connection.responseCode
        val responseBody = (if (status >= 400) connection.errorStream else connection.inputStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        connection.disconnect()
        return UploadResponse(status, responseBody)
    }

    private data class UploadResponse(val status: Int, val body: String)

    companion object {
        private const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024
        private const val TRANSPORT_MAX_FILE_SIZE_BYTES = 11 * 1024 * 1024
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        private val storageRoot: Path = Files.createTempDirectory("ultistats-http-storage-test-")

        @JvmStatic
        @DynamicPropertySource
        fun storageProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.storage.root") { storageRoot.toString() }
        }
    }
}
