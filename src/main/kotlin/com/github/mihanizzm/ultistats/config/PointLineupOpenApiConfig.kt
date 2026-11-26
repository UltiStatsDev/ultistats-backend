package com.github.mihanizzm.ultistats.config

import com.github.mihanizzm.ultistats.controller.POINT_LINEUPS_HEADER
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.media.ArraySchema
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

        schemas["PointLineupResponse"]?.properties?.set("effectiveTeams", ComposedSchema().apply {
            description = "Teams after replaying the point's substitutions, or null until confirmation."
            addAnyOfItem(ArraySchema().items(Schema<Any>().`$ref`("#/components/schemas/PointLineupTeam")))
            addAnyOfItem(Schema<Any>().apply { types = setOf("null") })
        })
        val pointLineups = "/api/v1/matches/{matchId}/point-lineups"
        api.paths["$pointLineups/{pointId}"]?.put?.lineupProtocol(lineupMutation = true)
        api.paths["$pointLineups/{pointId}/slots/{slotId}"]?.patch?.lineupProtocol(lineupMutation = true)
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
        for (command in listOf("start", "end")) {
            api.paths["/api/v1/matches/{id}/$command"]?.post?.lineupProtocol(lifecycle = true)
        }
    }

    private fun Operation.lineupProtocol(read: Boolean = false, lineupMutation: Boolean = false, lifecycle: Boolean = false) {
        addParametersItem(HeaderParameter().name(POINT_LINEUPS_HEADER).required(false)
            .description("Cumulative journal capabilities: 1 for raw temporary slot UUIDs, 2 for known-player substitutions, 3 for incoming reserves, 4 for POINT_RESULT.")
            .schema(StringSchema().addEnumItem("1").addEnumItem("2").addEnumItem("3").addEnumItem("4")))
        val details = buildList {
            if (read) add("POINT_LINEUP_CLIENT_REQUIRED when this response contains temporary actor UUIDs and the capability header is absent.")
            if (!read && !lineupMutation && !lifecycle) add(
                "POINT_LINEUP_CLIENT_REQUIRED for a managed journal without capability. " +
                "POINT_LINEUP_CONFLICT for a missing lineup, actor outside its point, or an invalid boundary edit. " +
                "POINT_LINEUP_WRITE_DISABLED for a new temporary actor write while the feature is off.",
            )
            if (!lifecycle) add(
                "POINT_LINEUP_CLIENT_REQUIRED for active substitutions without capability 2, 3 or 4, or active incoming reserves without capability 3 or 4.",
            )
            if (!lineupMutation) add(
                "POINT_RESULT_CLIENT_REQUIRED when the journal contains an active POINT_RESULT and capability 4 is absent. " +
                    "The conflict uses Cache-Control: no-store and varies by $POINT_LINEUPS_HEADER.",
            )
        }
        val protocol = details.joinToString(" ")
        val existing = responses["409"]
        if (existing != null) {
            existing.description += " $protocol"
        } else {
            responses.addApiResponse("409", ApiResponse().description(protocol).content(Content().addMediaType(
                "application/problem+json", MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/ProblemDetail")),
            )))
        }
    }
}
