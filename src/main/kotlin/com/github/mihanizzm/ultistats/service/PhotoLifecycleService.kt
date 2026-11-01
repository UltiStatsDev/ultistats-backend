package com.github.mihanizzm.ultistats.service

import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
class PhotoLifecycleService(
    private val fileStorageService: FileStorageService,
) {
    private val log = LoggerFactory.getLogger(PhotoLifecycleService::class.java)
    fun replace(
        currentUrl: String?,
        file: MultipartFile,
        persistUrl: (String?) -> Unit,
    ): String {
        val newUrl = fileStorageService.upload(file)
        try {
            persistUrl(newUrl)
        } catch (operationError: RuntimeException) {
            cleanup(newUrl, operationError)
            throw operationError
        }
        completeWithTransaction(currentUrl, newUrl)
        return newUrl
    }

    fun delete(
        currentUrl: String,
        persistUrl: (String?) -> Unit,
    ) {
        persistUrl(null)
        completeWithTransaction(currentUrl, null)
    }

    private fun completeWithTransaction(oldUrl: String?, stagedUrl: String?) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            !TransactionSynchronizationManager.isSynchronizationActive()) {
            oldUrl?.let(::bestEffortDelete)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                // The DB result is canonical now. Cleanup failure cannot undo it or delete the new file.
                oldUrl?.let(::bestEffortDelete)
            }

            override fun afterCompletion(status: Int) {
                if (stagedUrl == null) return
                when (status) {
                    TransactionSynchronization.STATUS_ROLLED_BACK -> bestEffortDelete(stagedUrl)
                    TransactionSynchronization.STATUS_UNKNOWN ->
                        log.warn("Photo transaction outcome is unknown; retained staged upload for reconciliation: {}", stagedUrl)
                }
            }
        })
    }

    private fun bestEffortDelete(url: String) {
        try {
            fileStorageService.delete(url)
        } catch (cleanupError: RuntimeException) {
            // Retain the file rather than turn a committed operation into a false rollback.
            log.warn("Photo completion cleanup failed; retained storage object: {}", url, cleanupError)
        }
    }

    private fun cleanup(url: String, originalError: RuntimeException) {
        try {
            fileStorageService.delete(url)
        } catch (cleanupError: RuntimeException) {
            originalError.addSuppressed(cleanupError)
        }
    }
}
