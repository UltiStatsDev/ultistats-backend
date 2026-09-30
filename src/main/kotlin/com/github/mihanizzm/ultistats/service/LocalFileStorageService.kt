package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.config.StorageProperties
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID


@Service
@Primary
class LocalFileStorageService(
    storageProperties: StorageProperties,
) : FileStorageService {
    private val root: Path = storageProperties.root.toAbsolutePath().normalize()

    override fun upload(file: MultipartFile): String {
        validate(!file.isEmpty, "File must not be empty")
        validate(file.size <= MAX_FILE_SIZE_BYTES, "File exceeds maximum size of 10 MiB")

        val content = file.inputStream.use { input ->
            input.readNBytes(MAX_FILE_SIZE_BYTES + 1)
        }
        validate(content.size <= MAX_FILE_SIZE_BYTES, "File exceeds maximum size of 10 MiB")

        val format = ImageFormat.detectBySignature(content)
            ?: throw InvalidFileUploadException("File signature is not supported")
        validate(
            file.contentType == format.contentType,
            "File content does not match declared content type ${file.contentType}",
        )

        try {
            Files.createDirectories(root)

            val key = "${UUID.randomUUID()}.${format.extension}"
            val path = root.resolve(key).normalize()
            check(path.startsWith(root)) { "Generated storage path escapes configured root" }

            Files.write(path, content, StandardOpenOption.CREATE_NEW)

            return "/uploads/$key"
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    override fun delete(key: String?) {
        try {
            Files.deleteIfExists(root.resolve(key))
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    override fun getUrl(key: String?): String? {
        return "/uploads/$key"
    }

    private fun validate(condition: Boolean, detail: String) {
        if (!condition) throw InvalidFileUploadException(detail)
    }

    private enum class ImageFormat(
        val contentType: String,
        val extension: String,
    ) {
        JPEG("image/jpeg", "jpg"),
        PNG("image/png", "png"),
        WEBP("image/webp", "webp"),
        ;

        companion object {
            fun detectBySignature(content: ByteArray): ImageFormat? = when {
                content.startsWith(0xFF, 0xD8, 0xFF) -> JPEG
                content.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
                content.startsWith(0x52, 0x49, 0x46, 0x46) &&
                    content.hasBytesAt(8, 0x57, 0x45, 0x42, 0x50) -> WEBP
                else -> null
            }
        }
    }

    private companion object {
        const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024

        fun ByteArray.startsWith(vararg signature: Int): Boolean = hasBytesAt(0, *signature)

        fun ByteArray.hasBytesAt(offset: Int, vararg expected: Int): Boolean =
            size >= offset + expected.size && expected.indices.all { index ->
                this[offset + index].toInt() and 0xFF == expected[index]
            }
    }
}
