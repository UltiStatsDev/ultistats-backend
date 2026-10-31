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
        // Reuse v1 leaves without inheriting the union: client generators infer
        // an allOf child as another discriminator variant ("BatchEventRequest").
        val create = api.components.schemas.getValue("CreateEventRequest")
        api.components.addSchemas("BatchEventRequest", ComposedSchema().apply {
            oneOf = create.oneOf
            discriminator = create.discriminator
            properties = mapOf("eventId" to create.properties.getValue("eventId"))
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
