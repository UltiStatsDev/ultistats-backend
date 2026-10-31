package com.github.mihanizzm.ultistats.config

import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.StringSchema
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
                val commonType = leaf.properties.getValue("type")
                // Json.mapper() uses OpenAPI 3.0 deserialization. Cloning a 3.1
                // schema through it loses the string type and defaults to object.
                val type = StringSchema().apply {
                    description = commonType.description
                    example = commonType.example
                }
                type.enum = base.discriminator.mapping.filterValues {
                    it == reference.`$ref`
                }.keys.toList()
                leaf.properties["type"] = type
                schemas[name] = leaf
            }
        }
    }
}
