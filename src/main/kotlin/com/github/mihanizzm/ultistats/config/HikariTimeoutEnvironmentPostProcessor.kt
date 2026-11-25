package com.github.mihanizzm.ultistats.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.boot.env.SystemEnvironmentPropertySourceEnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

class HikariTimeoutEnvironmentPostProcessor : EnvironmentPostProcessor, Ordered {
    override fun getOrder(): Int = SystemEnvironmentPropertySourceEnvironmentPostProcessor.DEFAULT_ORDER - 1

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
        val source = environment.propertySources[name] as? SystemEnvironmentPropertySource ?: return
        val legacyTimeout = source.source["SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT"] ?: return

        // Existing installations use a spelling that Spring reads as connection.timeout.
        // That path calls getConnection() while the rest of the pool is still being configured.
        val variables = source.source.toMutableMap()
        variables.remove("SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT")
        variables.putIfAbsent("SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT", legacyTimeout)
        environment.propertySources.replace(name, SystemEnvironmentPropertySource(name, variables))
    }
}
