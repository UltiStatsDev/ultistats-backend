package com.github.mihanizzm.ultistats.obs

import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ObsSourceKeyOpenApi {
    @Bean
    fun obsCurrentSourceKeySchema(): OpenApiCustomizer = OpenApiCustomizer { api ->
        mapOf(
            "ObsAutoScoreboardSnapshot" to "ObsExactScoreboardSnapshot",
            "ObsAutoInfographicsSnapshot" to "ObsInfographicsSnapshot",
            "ObsAutoEventLogSnapshot" to "ObsExactEventLogSnapshot",
        ).forEach { (envelope, snapshot) ->
            api.components.schemas[envelope]?.apply {
                addRequiredItem("snapshot")
                properties["snapshot"] = ComposedSchema().apply {
                    addAnyOfItem(Schema<Any>().`$ref`("#/components/schemas/$snapshot"))
                    addAnyOfItem(Schema<Any>().apply { types = setOf("null") })
                }
            }
        }
        api.components.schemas[ObsCurrentSourceKey::class.java.simpleName]?.apply {
            // The wire envelope always includes these fields, including their null states.
            addRequiredItem("key")
            addRequiredItem("url")
            properties["key"] = ComposedSchema().apply {
                addAnyOfItem(Schema<Any>().`$ref`("#/components/schemas/ObsSourceKeyMetadata"))
                addAnyOfItem(Schema<Any>().apply { types = setOf("null") })
            }
            properties["url"]?.types = setOf("string", "null")
        }
    }
}
