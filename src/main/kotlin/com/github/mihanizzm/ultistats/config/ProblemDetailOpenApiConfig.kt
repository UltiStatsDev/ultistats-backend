package com.github.mihanizzm.ultistats.config

import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ProblemDetailOpenApiConfig {
    @Bean
    fun problemDetailOpenApi(): OpenApiCustomizer = OpenApiCustomizer { api ->
        // Spring's URI property is a URI reference: request paths are relative.
        // Correct documentation only; the ProblemDetail serializer stays unchanged.
        listOf("ProblemDetail", "EventBatchProblem").forEach { name ->
            api.components.schemas[name]?.properties?.get("instance")?.apply {
                format = "uri-reference"
                description = "URI reference identifying this occurrence, usually the relative request path."
            }
        }
    }
}
