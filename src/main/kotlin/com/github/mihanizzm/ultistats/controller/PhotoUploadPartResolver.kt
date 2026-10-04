package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.service.InvalidFileUploadException
import org.springframework.web.multipart.MultipartFile

internal fun resolvePhotoUploadPart(
    file: MultipartFile?,
    legacyMultipartFile: MultipartFile?,
): MultipartFile = when {
    file != null && legacyMultipartFile != null ->
        throw InvalidFileUploadException("Provide only one multipart file part")
    file != null -> file
    legacyMultipartFile != null -> legacyMultipartFile
    else -> throw InvalidFileUploadException("Multipart file part is required")
}
