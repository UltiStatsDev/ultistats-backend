package com.github.mihanizzm.ultistats.auth.config

import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AuthOpenApiConfiguration {
    @Bean fun authContextOpenApi():OpenApiCustomizer=OpenApiCustomizer { api ->
        api.components.addSecuritySchemes("obsSourceBearer", SecurityScheme()
            .type(SecurityScheme.Type.HTTP).scheme("bearer")
            .description("Opaque OBS source key scoped to an exact match scoreboard."))
        api.components.addSecuritySchemes("staffSession", SecurityScheme()
            .type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.COOKIE).name("__Host-ULTISTATS_SESSION")
            .description("Existing staff session. Mutations also require X-CSRF-TOKEN and captured installation, account and session IDs."))
        // OpenAPI 3.1 $ref siblings do not make the referenced account nullable.
        api.components.schemas["AuthContext"]?.properties?.set("account",ComposedSchema().apply {
            addAnyOfItem(Schema<Any>().`$ref`("#/components/schemas/AccountView"))
            addAnyOfItem(Schema<Any>().apply { types=setOf("null") })
        })
    }
}
