package com.github.mihanizzm.ultistats.auth.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/** Staff document navigation only. API failures and captured intents remain raw problems. */
interface AuthHtmlEntryNavigation {
    fun redirect(request:HttpServletRequest,response:HttpServletResponse,problem:AuthProblem):Boolean
}
