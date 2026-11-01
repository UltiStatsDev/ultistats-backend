package com.github.mihanizzm.ultistats.auth.config

import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AuthOpenApiConfiguration {
    @Bean fun authContextOpenApi():OpenApiCustomizer=OpenApiCustomizer { api ->
        // OpenAPI 3.1 $ref siblings do not make the referenced account nullable.
        api.components.schemas["AuthContext"]?.properties?.set("account",ComposedSchema().apply {
            addAnyOfItem(Schema<Any>().`$ref`("#/components/schemas/AccountView"))
            addAnyOfItem(Schema<Any>().apply { types=setOf("null") })
        })
    }
}
