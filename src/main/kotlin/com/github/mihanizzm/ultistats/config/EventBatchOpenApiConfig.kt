package com.github.mihanizzm.ultistats.config

import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.BooleanSchema
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.DateTimeSchema
import io.swagger.v3.oas.models.media.Discriminator
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.UUIDSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
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
        val substitution = "#/components/schemas/PlayerSubstitutionBatchEvent"
        api.components.addSchemas("PlayerSubstitutionBatchEvent", ObjectSchema().apply {
            description = "Replace an on-field actor in a confirmed active point, after PULL and before its closing score."
            properties = mapOf(
                "type" to StringSchema().addEnumItem("PLAYER_SUBSTITUTION"),
                "eventId" to UUIDSchema().description("Stable client-generated event UUID for exact batch replay."),
                "occurredAt" to DateTimeSchema().description("Occurrence time in ISO-8601 UTC, no earlier than the previous event."),
                "fromParticipantId" to UUIDSchema().description("Outgoing on-field roster participant or temporary slot UUID."),
                "toParticipantId" to UUIDSchema().description("Incoming off-field PLAYER participant UUID, or a fresh match-scoped slot UUID with createIncomingReserve true."),
                "createIncomingReserve" to BooleanSchema().apply {
                    addEnumItem(true)
                    description = "Capability 3 only. When present, atomically introduces the incoming slot in the outgoing actor's team."
                },
            )
            required = properties.keys.filterNot { it == "createIncomingReserve" }
        })
        api.components.addSchemas("BatchEventRequest", ComposedSchema().apply {
            oneOf = create.oneOf + Schema<Any>().`$ref`(substitution)
            discriminator = Discriminator().propertyName("type").mapping(
                create.discriminator.mapping + ("PLAYER_SUBSTITUTION" to substitution),
            )
            properties = mapOf("eventId" to StringSchema().apply {
                format = "uuid"
                description = "Required stable client-generated event UUID. Reuse the same eventId when retrying the same batch; the server does not generate it for v2."
                example = create.properties.getValue("eventId").example
            })
            required = listOf("eventId")
            description = "Event payloads with a mandatory stable client eventId. PLAYER_SUBSTITUTION is batch-only and requires capability 2 or 3. Incoming reserve creation requires capability 3."
        })
        api.components.schemas["CreateEventBatchRequest"]?.properties?.set("events", ArraySchema().apply {
            minItems = 1
            maxItems = 100
            items = Schema<Any>().`$ref`("#/components/schemas/BatchEventRequest")
        })
    }
}
