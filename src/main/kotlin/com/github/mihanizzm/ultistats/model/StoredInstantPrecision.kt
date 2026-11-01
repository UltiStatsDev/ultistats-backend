package com.github.mihanizzm.ultistats.model

import java.time.Instant

/** Restore known input precision only while it agrees with the physical PG timestamp.
 * Absent/invalid/stale metadata preserves the known stored value, never invented nanos.
 */
internal fun Instant?.withOriginalPrecision(originalText: String?): Instant? {
    if (this == null) return null
    val original = originalText?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return this
    val rounded = Instant.ofEpochSecond(original.epochSecond, ((original.nano + 500L) / 1000) * 1000)
    return if (this == original || this == rounded) original else this
}
