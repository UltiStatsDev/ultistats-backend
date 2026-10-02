package com.github.mihanizzm.ultistats.service

import org.springframework.web.multipart.MultipartFile

interface FileStorageService {
    fun upload(file: MultipartFile): String

    fun delete(url: String)
}
