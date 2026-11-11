package com.github.mihanizzm.ultistats.config

import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class EventPolymorphismOpenApiConfig {
    @Bean
    fun eventPolymorphismOpenApi(): OpenApiCustomizer = OpenApiCustomizer { api ->
        listOf("CreateEventRequest", "UpdateEventRequest", "EventResponse").forEach {
            flattenDiscriminatedLeaves(api.components.schemas, it)
        }
    }
}

/** A union is not a superclass: remove the recursive allOf and preserve its shared fields. */
internal fun flattenDiscriminatedLeaves(schemas: MutableMap<String, Schema<*>>, baseName: String) {
    val base = schemas.getValue(baseName)
    val discriminator = base.discriminator.propertyName
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
        // Disjoint enums validate the oneOf. OpenAPI discriminator mappings alone are selection hints.
        val field = leaf.properties.getValue(discriminator)
        val tag = StringSchema().apply {
            description = field.description
            enum = base.discriminator.mapping.filterValues { it == reference.`$ref` }.keys.toList()
            field.example?.let { example = it.takeIf { it in enum } ?: enum.first() }
        }
        leaf.properties[discriminator] = tag
        schemas[name] = leaf
    }
}
