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
        .securityMatcher { request -> request.getHeader("X-Legacy-Domain-Test")=="domain-contract" }
        .csrf { it.disable() }.authorizeHttpRequests { it.anyRequest().permitAll() }.build()
    @Bean fun legacyDomainRequests()=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer { builder ->
        builder.defaultRequest<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/").header("X-Legacy-Domain-Test","domain-contract"))
    }
}
