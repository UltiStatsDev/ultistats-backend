package com.github.mihanizzm.ultistats.fixture

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

/** Explicitly imported by pre-auth domain-contract suites only; never production or auth suites. */
@TestConfiguration
class LegacyDomainHttpSecurity {
    @Bean @Order(-100)
    fun legacyDomainChain(http:HttpSecurity):SecurityFilterChain = http
        .csrf { it.disable() }.authorizeHttpRequests { it.anyRequest().permitAll() }.build()
}
