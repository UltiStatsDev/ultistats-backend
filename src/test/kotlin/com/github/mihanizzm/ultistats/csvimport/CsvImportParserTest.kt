package com.github.mihanizzm.ultistats.csvimport

import org.junit.jupiter.api.Test
import kotlin.test.*

class CsvImportParserTest {
    private val parser = CsvImportParser()
    private val applications = "Team Title,City\n Falcons , North \n"
    private val header = "Player First Name,Player Last Name,Team,Jersey Number\n"
    private fun parse(a: String = applications, r: String = header) = parser.parse(a.toByteArray(), r.toByteArray())

    @Test fun `quoted records BOM trim and ignored status and role preserve every row`() {
        val batch = parse("\uFEFFTeam Title,City,status\r\n\"Falcons, A\",North,rejected\r\n",
            header.trimEnd() + ",role\n\"Al\nex\",\"O\"\"Brien\",\"Falcons, A\", 0 ,coach\n")
        assertEquals("Falcons, A", batch.teams.single().name)
        assertEquals("Al\nex", batch.players.single().firstName)
        assertEquals("O\"Brien", batch.players.single().lastName)
        assertEquals(0, batch.players.single().number)
        assertEquals(2, batch.players.single().row)
        assertEquals(0, batch.players.single().teamIndex)
    }
    @Test fun `header only and duplicate unreferenced applications stay distinct`() {
        assertEquals(2, parse(applications + "Falcons,South\n").teams.size)
        assertTrue(parse().players.isEmpty())
    }
    @Test fun `number bounds and repeated names are retained`() {
        val batch = parse(r = header + "A,B,Falcons,\nA,B,Falcons,2147483647\n")
        assertEquals(listOf(null, Int.MAX_VALUE), batch.players.map { it.number })
    }
    @Test fun `invalid values headers links and syntax have safe located errors`() {
        listOf("-1", "2147483648", "1.5", "text").forEach { number ->
            val error = assertFailsWith<CsvImportException> { parse(r = header + "A,B,Falcons,$number\n") }
            assertEquals("Jersey Number", error.errors.single().column)
            assertEquals(2, error.errors.single().row)
        }
        listOf("falcons", "Unknown", "").forEach { team ->
            assertFailsWith<CsvImportException> { parse(r = header + "A,B,$team,\n") }
        }
        assertFailsWith<CsvImportException> { parse(applications + "Falcons,South\n", header + "A,B,Falcons,\n") }
        listOf("City\nNorth\n", "Team Title,Team Title,City\nA,A,B\n", "Team Title,City\n\"open,B",
            "Team Title,City\nA\"bad,B\n", "Team Title,City\n\"A\"junk,B\n", "Team Title,City\n,B\n",
            "Team Title,City\n${"A".repeat(256)},B\n", "Team Title,City\nA,${"B".repeat(128)}\n").forEach {
            assertFailsWith<CsvImportException> { parse(it) }
        }
        assertFailsWith<CsvImportException> { parser.parse(byteArrayOf(0xc3.toByte(), 0x28), header.toByteArray()) }
        assertFailsWith<CsvImportException> { parse(r = header + ",B,Falcons,\n") }
        assertFailsWith<CsvImportException> { parse(r = header + "A,,Falcons,\n") }
        assertFailsWith<CsvImportException> { parse(r = "") }
    }
    @Test fun `empty physical lines skip but quoted empty record is validated`() {
        val batch = parse(applications + "\n   \nOther,South\n")
        assertEquals(listOf(2, 5), batch.teams.map { it.row })
        assertFailsWith<CsvImportException> { parse(applications + "\"\"\n") }
    }
    @Test fun `record and byte bounds are inclusive`() {
        assertEquals(200, parse("Team Title,City\n" + (1..200).joinToString("") { "T$it,C\n" }).teams.size)
        assertEquals(5000, parse(r = header + "A,B,Falcons,\n".repeat(5000)).players.size)
        listOf({ parse("Team Title,City\n" + "A,B\n".repeat(201)) },
            { parse(r = header + "A,B,Falcons,\n".repeat(5001)) },
            { parse(applications + " ".repeat(1048577)) }).forEach {
            assertEquals(413, assertFailsWith<CsvImportException> { it() }.status.value())
        }
        val exact = "Team Title,City,ignored\nA,B,".let { it + "x".repeat(1048576 - it.length) }
        assertEquals(1, parse(exact).teams.size)
    }
}
