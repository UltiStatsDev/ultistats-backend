package com.github.mihanizzm.ultistats.auth.ingress

import com.github.mihanizzm.ultistats.auth.repository.AuthStore
import com.github.mihanizzm.ultistats.auth.service.AuthService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

class InstallationControllerTest {
    @Test
    fun `public metadata reads persistent UUID without creating admin or leaking private context`() {
        val dataSource = DriverManagerDataSource("jdbc:h2:mem:install_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "")
        ResourceDatabasePopulator(ClassPathResource("db/migration/V11__accounts_and_sessions.sql")).execute(dataSource)
        val jdbc = JdbcTemplate(dataSource)
        val store = AuthStore(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)))
        val service = AuthService(store, Argon2PasswordEncoder(16,32,1,19456,2), Clock.systemUTC())
        val installationId = service.installationId()
        val properties = AuthIngressProperties(publicOrigin = "http://192.0.2.1", secureOrigin = "https://staff.example.test")
        val mvc = MockMvcBuilders.standaloneSetup(InstallationController(service, ListenerAuthEntryClassifier(properties), properties)).build()
        repeat(2) {
            mvc.perform(get("/api/v1/installation").header("Host", "attacker.example.test")
                .header("X-Forwarded-Proto", "https").header("Cookie", "__Host-ULTISTATS_SESSION=forged"))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("""{"contractVersion":"1.0.0","installationId":"$installationId","entryMode":"ANONYMOUS","publicOrigin":"http://192.0.2.1","secureOrigin":"https://staff.example.test"}""", true))
        }
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM auth_accounts", Long::class.java))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM auth_sessions", Long::class.java))
        assertEquals(installationId, service.installationId())
    }
}
