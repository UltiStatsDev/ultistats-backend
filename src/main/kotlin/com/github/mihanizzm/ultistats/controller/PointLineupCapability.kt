package com.github.mihanizzm.ultistats.controller

import jakarta.servlet.http.HttpServletRequest

const val POINT_LINEUPS_HEADER = "X-UltiStats-Point-Lineups"

internal fun HttpServletRequest.pointLineupCapable(): Boolean =
    getHeaders(POINT_LINEUPS_HEADER).toList().singleOrNull() == "1"
