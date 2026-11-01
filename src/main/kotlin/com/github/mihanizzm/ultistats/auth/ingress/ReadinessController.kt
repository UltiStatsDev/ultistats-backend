package com.github.mihanizzm.ultistats.auth.ingress

import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.ReadinessState
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.SQLException
import javax.sql.DataSource

@RestController
class ReadinessController(private val dataSource: DataSource, private val availability: ApplicationAvailability) {
    @GetMapping("/health/ready")
    fun ready(): ResponseEntity<Map<String, String>> {
        // ACCEPTING_TRAFFIC is published after Flyway/schema validation and
        // application startup finish. A live DB check also detects later loss.
        val up = availability.readinessState == ReadinessState.ACCEPTING_TRAFFIC && databaseReady()
        return ResponseEntity.status(if (up) 200 else 503).cacheControl(CacheControl.noStore())
            .body(mapOf("status" to if (up) "UP" else "DOWN"))
    }

    private fun databaseReady(): Boolean = try {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.queryTimeout = 2
                statement.executeQuery("SELECT 1").use { result -> result.next() && result.getInt(1) == 1 }
            }
        }
    } catch (_: SQLException) { false }
}
