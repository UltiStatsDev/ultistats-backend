package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.service.PointLineupClient
import jakarta.servlet.http.HttpServletRequest

const val POINT_LINEUPS_HEADER = "X-UltiStats-Point-Lineups"

internal fun HttpServletRequest.pointLineupClient(): PointLineupClient =
    when (getHeaders(POINT_LINEUPS_HEADER).toList().singleOrNull()) {
        "1" -> PointLineupClient.LINEUPS
        "2" -> PointLineupClient.SUBSTITUTIONS
        "3" -> PointLineupClient.INCOMING_RESERVES
        else -> PointLineupClient.LEGACY
    }
