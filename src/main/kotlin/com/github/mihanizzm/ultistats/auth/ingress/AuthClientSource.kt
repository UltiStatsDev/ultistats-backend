package com.github.mihanizzm.ultistats.auth.ingress

import jakarta.servlet.http.HttpServletRequest

/** Rate-limit source from the raw socket or a verified private ingress peer. */
interface AuthClientSource {
    fun source(request: HttpServletRequest): String
}
