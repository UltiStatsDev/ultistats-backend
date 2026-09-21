package com.github.mihanizzm.ultistats.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

@ConfigurationProperties(prefix = "app.storage")
data class StorageProperties(
    val root: Path = Path.of("uploads"),
) {
    fun resourceLocation(): String = root.toAbsolutePath().normalize().toUri().toString()
        .let { if (it.endsWith('/')) it else "$it/" }
}
