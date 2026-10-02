package com.github.mihanizzm.ultistats.service

import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

@Service
class PhotoLifecycleService(
    private val fileStorageService: FileStorageService,
) {
    fun replace(
        currentUrl: String?,
        file: MultipartFile,
        persistUrl: (String?) -> Unit,
    ): String {
        val newUrl = fileStorageService.upload(file)
        try {
            persistUrl(newUrl)
            currentUrl?.let(fileStorageService::delete)
        } catch (operationError: RuntimeException) {
            cleanup(newUrl, operationError)
            throw operationError
        }
        return newUrl
    }

    fun delete(
        currentUrl: String,
        persistUrl: (String?) -> Unit,
    ) {
        persistUrl(null)
        fileStorageService.delete(currentUrl)
    }

    private fun cleanup(url: String, originalError: RuntimeException) {
        try {
            fileStorageService.delete(url)
        } catch (cleanupError: RuntimeException) {
            originalError.addSuppressed(cleanupError)
        }
    }
}
