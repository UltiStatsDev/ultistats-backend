package com.github.mihanizzm.ultistats.service

import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.util.FileSystemUtils
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
    fun `файл сохраняется в настроенную директорию`() {
        val file = MockMultipartFile(
            "file",
            "photo.jpg",
            "image/jpeg",
            "photo-content".toByteArray(),
        )

        val url = storage.upload(file)

        val storedFiles = Files.list(storageRoot).use { it.toList() }
        assertThat(url).startsWith("/uploads/")
        assertThat(storedFiles).hasSize(1)
        assertThat(Files.readString(storedFiles.single())).isEqualTo("photo-content")
    }

    @Test
    fun `статический ресурс читается из настроенной директории`() {
        Files.writeString(storageRoot.resolve("served-photo.txt"), "served-content")

        mockMvc.perform(get("/uploads/served-photo.txt"))
            .andExpect(status().isOk)
            .andExpect(content().string("served-content"))
    }

    companion object {
        private val storageRoot: Path = Files.createTempDirectory("ultistats-storage-test-")

        @JvmStatic
        @DynamicPropertySource
        fun storageProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.storage.root") { storageRoot.toString() }
        }
    }
}
