package com.github.mihanizzm.ultistats.service.patch

import org.openapitools.jackson.nullable.JsonNullable

sealed interface FieldPatch<out T> {
    data object Omitted : FieldPatch<Nothing>
    data object Null : FieldPatch<Nothing>
    data class Value<T>(val value: T) : FieldPatch<T>
}

fun <T : Any> JsonNullable<T>.toFieldPatch(): FieldPatch<T> {
    if (!isPresent) return FieldPatch.Omitted
    val value = get()
    return if (value == null) FieldPatch.Null else FieldPatch.Value(value)
}
