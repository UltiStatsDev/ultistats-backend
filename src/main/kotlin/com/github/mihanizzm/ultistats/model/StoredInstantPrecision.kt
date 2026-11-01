package com.github.mihanizzm.ultistats.model

import java.time.Instant

/** Restore only known precision which agrees with the physical PG timestamp. */
internal fun Instant?.withOriginalPrecision(originalText: String?): Instant? =
    restoreOriginal(originalText?.let { runCatching { Instant.parse(it) }.getOrNull() })

internal fun Instant?.withOriginalPrecision(epochSecond: Long?, nano: Int?): Instant? =
    restoreOriginal(if (epochSecond == null || nano == null || nano !in 0..999999999) null
        else runCatching { Instant.ofEpochSecond(epochSecond, nano.toLong()) }.getOrNull())

private fun Instant?.restoreOriginal(original: Instant?): Instant? {
    if (this == null || original == null) return this
    // Round the non-negative fractional part, then carry. Rounding a signed whole
    // epoch instead disagrees with JDBC at pre-epoch half-microsecond boundaries.
    val rounded = runCatching {
        Instant.ofEpochSecond(original.epochSecond, ((original.nano + 500L) / 1000) * 1000)
    }.getOrNull()
    return if (this == original || this == rounded) original else this
}
