package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

enum class ObsCategory { GOAL, BLOCK, INTERCEPTION, CALLAHAN }
enum class ObsCorner { TOP_RIGHT, TOP_LEFT, BOTTOM_RIGHT, BOTTOM_LEFT }

data class ObsConfig(
    @field:JsonSetter(contentNulls = Nulls.FAIL)
    val categories: Set<ObsCategory> = ObsCategory.entries.toSet(),
    @field:Min(1) @field:Max(20) val maxRows: Int = 5,
    @field:Min(1) @field:Max(60) val ttlSeconds: Int = 8,
    val corner: ObsCorner = ObsCorner.TOP_RIGHT,
    @field:Pattern(regexp = "#[0-9a-fA-F]{6}") val backgroundColor: String = "#17202e",
    @field:Pattern(regexp = "#[0-9a-fA-F]{6}") val textColor: String = "#ffffff",
    @field:JsonSetter(contentNulls = Nulls.FAIL)
    @field:Size(max = 500) val teamColors: Map<UUID, String> = emptyMap(),
)
