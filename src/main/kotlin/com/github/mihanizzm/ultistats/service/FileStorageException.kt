package com.github.mihanizzm.ultistats.service

class FileStorageException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
