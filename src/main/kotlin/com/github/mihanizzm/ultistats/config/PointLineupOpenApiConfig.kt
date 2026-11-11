package com.github.mihanizzm.ultistats.config

import com.github.mihanizzm.ultistats.controller.POINT_LINEUPS_HEADER
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.HeaderParameter
import io.swagger.v3.oas.models.responses.ApiResponse
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
        flattenDiscriminatedLeaves(schemas, "PortablePointLineupEntry")
        // An OpenAPI 3.1 $ref with a nullable sibling still requires the referenced object.
        schemas["PointLineupResponse"]?.properties?.set("lineup", ComposedSchema().apply {
            addAnyOfItem(Schema<Any>().`$ref`("#/components/schemas/PointLineup"))
            addAnyOfItem(Schema<Any>().apply { types = setOf("null") })
        })

        for (version in listOf("v1", "v2")) {
            val journal = "/api/$version/matches/{matchId}/events"
            api.paths[journal]?.get?.lineupProtocol(read = true)
            api.paths[journal]?.post?.lineupProtocol()
            api.paths["$journal/{eventId}"]?.let { event ->
                event.get?.lineupProtocol(read = true)
                event.patch?.lineupProtocol()
                event.delete?.lineupProtocol()
            }
            api.paths["$journal/batch"]?.post?.lineupProtocol()
            api.paths["$journal/undo"]?.post?.lineupProtocol()
        }
    }

    private fun Operation.lineupProtocol(read: Boolean = false) {
        addParametersItem(HeaderParameter().name(POINT_LINEUPS_HEADER).required(false)
            .description("Send 1 when the client can replay raw temporary slot UUIDs in event participant fields.")
            .schema(StringSchema().addEnumItem("1")))
        val detail = if (read) {
            "POINT_LINEUP_CLIENT_REQUIRED when this response contains temporary actor UUIDs and the capability header is absent."
        } else {
            "POINT_LINEUP_CLIENT_REQUIRED for a managed journal without capability. " +
                "POINT_LINEUP_CONFLICT for a missing lineup, actor outside its point, or an invalid boundary edit. " +
                "POINT_LINEUP_WRITE_DISABLED for a new temporary actor write while the feature is off."
        }
        val existing = responses["409"]
        if (existing != null) {
            existing.description += " $detail"
        } else {
            responses.addApiResponse("409", ApiResponse().description(detail).content(Content().addMediaType(
                "application/problem+json", MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/ProblemDetail")),
            )))
        }
    }
}
