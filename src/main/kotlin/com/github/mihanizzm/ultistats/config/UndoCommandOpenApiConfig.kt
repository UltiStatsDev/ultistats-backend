package com.github.mihanizzm.ultistats.config

import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class UndoCommandOpenApiConfig {
    @Bean
    fun undoCommandOpenApi(): OpenApiCustomizer = OpenApiCustomizer { api ->
        // A nullable reference needs a union: a $ref sibling type does not allow null.
        api.components.schemas["UndoCommandResponse"]?.properties?.set("rejection", ComposedSchema().apply {
            addAnyOfItem(Schema<Any>().`$ref`("#/components/schemas/UndoCommandRejection"))
            addAnyOfItem(Schema<Any>().apply { types = setOf("null") })
            description = "Null for APPLIED; immutable code and detail for REJECTED."
        })
    }
}
