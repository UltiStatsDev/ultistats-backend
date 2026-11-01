package com.github.mihanizzm.ultistats.auth.security

class AuthProblem(val status: Int, val code: String, val retryAfterSeconds: Long? = null,
    val rejectedBeforeExecution:Boolean=false) : RuntimeException(code)
