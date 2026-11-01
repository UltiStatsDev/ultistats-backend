package com.github.mihanizzm.ultistats.auth.security

import com.github.mihanizzm.ultistats.auth.model.AuthEntryMode
import jakarta.servlet.http.HttpServletRequest

interface AuthEntryClassifier { fun classify(request: HttpServletRequest): AuthEntryMode }
