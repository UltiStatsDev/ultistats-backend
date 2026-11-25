package com.github.mihanizzm.ultistats.csvimport

import com.fasterxml.jackson.annotation.JsonInclude
import org.apache.commons.csv.CSVFormat
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

const val CSV_FILE_LIMIT = 1024 * 1024

@JsonInclude(JsonInclude.Include.ALWAYS)
data class CsvImportError(val file: String, val row: Int?, val column: String?, val code: String)
class CsvImportException(val status: HttpStatus, val errors: List<CsvImportError>) : RuntimeException("CSV import rejected")
internal fun reject(file: String, row: Int? = null, column: String? = null, code: String,
    status: HttpStatus = HttpStatus.BAD_REQUEST): Nothing =
    throw CsvImportException(status, listOf(CsvImportError(file, row, column, code)))

data class CsvTeam(val row: Int, val name: String, val city: String?)
data class CsvPlayer(val row: Int, val firstName: String, val lastName: String, val teamIndex: Int, val number: Int?)
data class CsvBatch(val teams: List<CsvTeam>, val players: List<CsvPlayer>)

@Component
class CsvImportParser {
    fun parse(applications: ByteArray, rosters: ByteArray): CsvBatch {
        val teams = records(applications, "applications", listOf("Team Title", "City"), 200).map { record ->
            CsvTeam(record.row, record.required("Team Title", 255), record.value("City").takeIf { it.isNotEmpty() }
                ?.also { if (it.length > 127) reject("applications", record.row, "City", "TOO_LONG") })
        }
        val titles = teams.withIndex().groupBy { it.value.name }
        val players = records(rosters, "rosters", listOf("Player First Name", "Player Last Name", "Team", "Jersey Number"), 5000).map { record ->
            val matches = titles[record.value("Team")]
            if (matches.isNullOrEmpty()) reject("rosters", record.row, "Team", "TEAM_NOT_FOUND")
            if (matches.size != 1) reject("rosters", record.row, "Team", "TEAM_AMBIGUOUS")
            val number = record.value("Jersey Number").takeIf { it.isNotEmpty() }?.let {
                if (!it.all { c -> c in '0'..'9' }) reject("rosters", record.row, "Jersey Number", "INVALID_NUMBER")
                it.toIntOrNull() ?: reject("rosters", record.row, "Jersey Number", "INVALID_NUMBER")
            }
            CsvPlayer(record.row, record.required("Player First Name", 255), record.required("Player Last Name", 255), matches.single().index, number)
        }
        return CsvBatch(teams, players)
    }

    private data class Record(val file: String, val row: Int, val values: Map<String, String>) {
        fun value(column: String) = values.getValue(column).trim()
        fun required(column: String, max: Int): String = value(column).also {
            if (it.isEmpty()) reject(file, row, column, "REQUIRED")
            if (it.length > max) reject(file, row, column, "TOO_LONG")
        }
    }

    private fun records(bytes: ByteArray, file: String, columns: List<String>, limit: Int): List<Record> {
        if (bytes.size > CSV_FILE_LIMIT) reject(file, code = "FILE_TOO_LARGE", status = HttpStatus.PAYLOAD_TOO_LARGE)
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: CharacterCodingException) { reject(file, code = "INVALID_UTF8") }
        strictQuotes(text, file)
        var row = 1
        try {
            CSVFormat.RFC4180.builder().setIgnoreEmptyLines(false).setLenientEof(false).setTrailingData(false).get()
                .parse(text.reader()).use { csv ->
                    val iterator = csv.iterator()
                    if (!iterator.hasNext()) reject(file, code = "HEADER_REQUIRED")
                    val header = iterator.next().toList()
                    columns.forEach { column ->
                        if (header.count { it == column } != 1) reject(file, 1, column, "HEADER_REQUIRED_OR_DUPLICATE")
                    }
                    val result = mutableListOf<Record>()
                    while (iterator.hasNext()) {
                        row++
                        val csvRecord = iterator.next()
                        val values = csvRecord.toList()
                        if (values.size == 1 && values[0].isBlank() && text[csvRecord.characterPosition.toInt()] != '"') continue
                        if (result.size == limit) reject(file, row, code = "TOO_MANY_ROWS", status = HttpStatus.PAYLOAD_TOO_LARGE)
                        if (values.size != header.size) reject(file, row, code = "COLUMN_COUNT")
                        result += Record(file, row, columns.associateWith { values[header.indexOf(it)] })
                    }
                    return result
                }
        } catch (error: CsvImportException) { throw error }
        catch (_: RuntimeException) { reject(file, row, code = "INVALID_CSV") }
        catch (_: java.io.IOException) { reject(file, row, code = "INVALID_CSV") }
    }

    // Commons CSV deliberately accepts quotes in unquoted fields. Reject those too, without echoing input.
    private fun strictQuotes(text: String, file: String) {
        var state = 0 // field start, unquoted, quoted, after quote
        var row = 1
        var previousCr = false
        text.forEach { c ->
            when (state) {
                0 -> state = when (c) { '"' -> 2; ',', '\r', '\n' -> 0; else -> 1 }
                1 -> when (c) { '"' -> reject(file, row, code = "INVALID_CSV"); ',', '\r', '\n' -> state = 0 }
                2 -> if (c == '"') state = 3
                3 -> state = when (c) { '"' -> 2; ',', '\r', '\n' -> 0; else -> reject(file, row, code = "INVALID_CSV") }
            }
            if (state != 2 && (c == '\r' || c == '\n' && !previousCr)) row++
            previousCr = c == '\r'
        }
        if (state == 2) reject(file, row, code = "INVALID_CSV")
    }
}
