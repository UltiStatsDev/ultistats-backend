package com.github.mihanizzm.ultistats.config

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HikariEnvironmentStartupTest {
    @Test
    fun `existing hosted timeout binds without starting the pool during configuration`() =
        verifyTimeout(mapOf("SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT" to "3000"), 3000)

    @Test
    fun `canonical environment spelling takes precedence over the legacy spelling`() =
        verifyTimeout(
            mapOf(
                "SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT" to "3000",
                "SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT" to "4500",
            ),
            4500,
        )

    private fun verifyTimeout(variables: Map<String, String>, timeout: Long) {
        val name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
        val environment = StandardEnvironment().apply {
            propertySources.replace(name, SystemEnvironmentPropertySource(name, variables))
        }
        SpringApplicationBuilder(DataSourceAutoConfiguration::class.java)
            .web(WebApplicationType.NONE)
            .environment(environment)
            .run(
                "--spring.config.location=file:src/main/resources/application.yaml",
                "--spring.datasource.url=jdbc:h2:mem:hikari-startup",
                "--spring.datasource.driver-class-name=org.h2.Driver",
            ).use { context ->
                val pool = context.getBean(HikariDataSource::class.java)
                assertNull(pool.hikariPoolMXBean, "Configuration binding must leave the pool unopened")
                assertEquals(timeout, pool.connectionTimeout)
                assertEquals("false", pool.dataSourceProperties["logServerErrorDetail"].toString())
            }
    }
}
