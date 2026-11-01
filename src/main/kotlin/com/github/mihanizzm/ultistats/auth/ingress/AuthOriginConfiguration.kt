package com.github.mihanizzm.ultistats.auth.ingress

/** Configured origins only; request headers never supply installation origins. */
interface AuthOriginConfiguration {
    fun secureOrigins(): Set<String>
    fun publicOrigin(): String
    fun secureOrigin(): String
}
