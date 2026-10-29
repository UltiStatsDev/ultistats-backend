package com.github.mihanizzm.ultistats.config

import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class EventBatchOpenApiConfig {
    @Bean
    fun eventBatchOpenApi(): OpenApiCustomizer = OpenApiCustomizer { api ->
        // Reuse v1's polymorphic shapes while requiring UUID only at the v2 boundary.
        // springdoc does not register an annotation-only allOf item schema reliably.
        api.components.addSchemas("BatchEventRequest", ComposedSchema().apply {
            addAllOfItem(Schema<Any>().`$ref`("#/components/schemas/CreateEventRequest"))
            required = listOf("eventId")
            description = "Same event payloads as v1, with a mandatory stable client eventId."
        })
        api.components.schemas["CreateEventBatchRequest"]?.properties?.set("events", ArraySchema().apply {
            minItems = 1
            maxItems = 100
            items = Schema<Any>().`$ref`("#/components/schemas/BatchEventRequest")
        })
    }
}
