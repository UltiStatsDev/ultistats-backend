package com.github.mihanizzm.ultistats.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.multipart.MultipartFile

@Schema(description = "Multipart-запрос загрузки фотографии")
data class PhotoUploadRequest(
    @field:Schema(type = "string", format = "binary")
    val file: MultipartFile,
)
