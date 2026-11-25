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
        api.components.schemas["ObsInfographicsSnapshot"]?.apply {
            addRequiredItem("leaderPresentation")
            properties["leaderPresentation"] = nullableRef("ObsLeaderPresentation")
        }
        mapOf(
            "ObsAutoLeadersSnapshot" to "ObsLeadersSnapshot",
            "ObsAutoScoreboardSnapshot" to "ObsExactScoreboardSnapshot",
            "ObsAutoInfographicsSnapshot" to "ObsInfographicsSnapshot",
            "ObsAutoEventLogSnapshot" to "ObsExactEventLogSnapshot",
        ).forEach { (envelope, snapshot) ->
            api.components.schemas[envelope]?.apply {
                addRequiredItem("snapshot")
                properties["snapshot"] = nullableRef(snapshot)
            }
        }
        api.components.schemas[ObsCurrentSourceKey::class.java.simpleName]?.apply {
            // The wire envelope always includes these fields, including their null states.
            addRequiredItem("key")
            addRequiredItem("url")
            properties["key"] = nullableRef("ObsSourceKeyMetadata")
            properties["url"]?.types = setOf("string", "null")
        }
    }
    private fun nullableRef(schema: String) = ComposedSchema().apply {
        addAnyOfItem(Schema<Any>().`$ref`("#/components/schemas/$schema"))
        addAnyOfItem(Schema<Any>().apply { types = setOf("null") })
    }
}
