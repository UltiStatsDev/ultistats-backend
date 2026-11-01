package com.github.mihanizzm.ultistats.auth

import com.github.mihanizzm.ultistats.UltistatsApplication
import com.github.mihanizzm.ultistats.auth.cli.OwnerAuthCli
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.*

@EnabledIfEnvironmentVariable(named="TEST_POSTGRES_URL",matches=".+")
class OwnerAuthCliPostgresTest {
    @Test fun `full nonweb application can bootstrap from input and restart without reset or listener`() {
        val schema="auth152_cli_"+UUID.randomUUID().toString().replace("-","")
        fun application()=SpringApplicationBuilder(UltistatsApplication::class.java).web(WebApplicationType.NONE)
            .profiles("postgres-list").properties(mapOf(
                "spring.datasource.hikari.connection-init-sql" to "SET search_path TO $schema",
                "spring.flyway.schemas" to schema,"spring.flyway.default-schema" to schema,
                "spring.jpa.properties.hibernate.default_schema" to schema)).run()
        var id:UUID?=null
        application().use { context ->
            assertFalse(context is WebServerApplicationContext)
            val output=mutableListOf<String>()
            val secret="CLI-synthetic-152".toCharArray()
            context.getBean(OwnerAuthCli::class.java).execute(OwnerAuthCli.parse(listOf("owner","bootstrap","--login=cli152","--display-name=CLI owner","--stdin")),{secret},output::add)
            assertTrue(secret.all { it=='\u0000' })
            assertFalse(output.joinToString().contains("CLI-synthetic-152"))
            id=context.getBean(JdbcTemplate::class.java).queryForObject("SELECT id FROM auth_accounts WHERE login='cli152'",UUID::class.java)
        }
        application().use { context ->
            assertFalse(context is WebServerApplicationContext)
            val jdbc=context.getBean(JdbcTemplate::class.java)
            assertEquals(id,jdbc.queryForObject("SELECT id FROM auth_accounts WHERE login='cli152'",UUID::class.java))
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM auth_accounts",Int::class.java))
        }
    }
}
