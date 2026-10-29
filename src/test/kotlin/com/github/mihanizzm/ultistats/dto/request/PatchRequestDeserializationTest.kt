package com.github.mihanizzm.ultistats.dto.request

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.mihanizzm.ultistats.service.patch.FieldPatch
import com.github.mihanizzm.ultistats.service.patch.toFieldPatch
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals

@SpringBootTest
class PatchRequestDeserializationTest {
    @Autowired lateinit var objectMapper: ObjectMapper

    @Test
    fun `omitted patch property remains distinguishable from explicit null and value`() {
        val omitted = objectMapper.readValue<PatchTeamRequest>("{}")
        val explicitNull = objectMapper.readValue<PatchTeamRequest>("""{"city":null}""")
        val value = objectMapper.readValue<PatchTeamRequest>("""{"city":"Novosibirsk"}""")

        assertEquals(FieldPatch.Omitted, omitted.city.toFieldPatch())
        assertEquals(FieldPatch.Null, explicitNull.city.toFieldPatch())
        assertEquals(FieldPatch.Value("Novosibirsk"), value.city.toFieldPatch())
    }

    @Test
    fun `every patch request defaults all properties to omitted`() {
        val player = objectMapper.readValue<PatchPlayerRequest>("{}")
        val team = objectMapper.readValue<PatchTeamRequest>("{}")
        val match = objectMapper.readValue<PatchMatchRequest>("{}")

        assertEquals(FieldPatch.Omitted, player.firstName.toFieldPatch())
        assertEquals(FieldPatch.Omitted, player.lastName.toFieldPatch())
        assertEquals(FieldPatch.Omitted, team.name.toFieldPatch())
        assertEquals(FieldPatch.Omitted, team.city.toFieldPatch())
        assertEquals(FieldPatch.Omitted, match.teamIds.toFieldPatch())
        assertEquals(FieldPatch.Omitted, match.playersPerTeam.toFieldPatch())
        assertEquals(FieldPatch.Omitted, match.plannedStartTimestamp.toFieldPatch())
    }
}
