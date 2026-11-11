package com.github.mihanizzm.ultistats.config

import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PointLineupOpenApiConfig {
    @Bean
    fun pointLineupOpenApi() = OpenApiCustomizer { api ->
        val schemas = api.components.schemas
        flattenDiscriminatedLeaves(schemas, "ConfirmPointLineupEntry")
        flattenDiscriminatedLeaves(schemas, "PointLineupEntry")
        // An OpenAPI 3.1 $ref with a nullable sibling still requires the referenced object.
        schemas["PointLineupResponse"]?.properties?.set("lineup", ComposedSchema().apply {
            addAnyOfItem(Schema<Any>().`$ref`("#/components/schemas/PointLineup"))
            addAnyOfItem(Schema<Any>().apply { types = setOf("null") })
        })
    }
}
