package com.github.mihanizzm.ultistats.config

import com.fasterxml.jackson.core.type.TypeReference
import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class EventPolymorphismOpenApiConfig {
    @Bean
    fun eventPolymorphismOpenApi(): OpenApiCustomizer = OpenApiCustomizer { api ->
        val schemas = api.components.schemas
        // A union is not a superclass: inheriting it makes each leaf evaluate the
        // same oneOf again on the same JSON instance. Copy only common fields.
        listOf("CreateEventRequest", "UpdateEventRequest", "EventResponse").forEach { baseName ->
            val base = schemas.getValue(baseName)
            base.oneOf.forEach { reference ->
                val name = reference.`$ref`.substringAfterLast('/')
                val original = schemas.getValue(name)
                val leaf = ObjectSchema().apply {
                    description = original.description
                    properties = LinkedHashMap(base.properties.orEmpty())
                    original.allOf.orEmpty().filter { it.`$ref` == null }.forEach {
                        properties.putAll(it.properties.orEmpty())
                    }
                    properties.putAll(original.properties.orEmpty())
                    required = (base.required.orEmpty() + original.required.orEmpty()).distinct()
                }
                // OpenAPI's discriminator is a selection hint, not validation.
                // Disjoint enums make the oneOf meaningful to JSON Schema too.
                val type = Json.mapper().convertValue(leaf.properties.getValue("type"), object : TypeReference<Schema<Any>>() {})
                type.enum = base.discriminator.mapping.filterValues {
                    it == reference.`$ref`
                }.keys.toList()
                leaf.properties["type"] = type
                schemas[name] = leaf
            }
        }
    }
}
