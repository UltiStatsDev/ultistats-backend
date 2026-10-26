package com.github.mihanizzm.ultistats.validation.query

class InvalidQueryParameterException(message: String) : IllegalArgumentException(message)

/** Preserve the distinction between omitted values and values Spring may default or collapse. */
fun validateScalarQueryParameters(parameters: Map<String, Array<String>>, names: Set<String>) {
    for (key in parameters.keys) {
        val name = key.removePrefix("!").removePrefix("_").substringBefore('[')
        if (name in names && key != name) {
            throw InvalidQueryParameterException("Invalid '$name' parameter name. Use '$name' without prefixes or brackets")
        }
    }
    for (name in names) {
        val values = parameters[name] ?: continue
        if (values.size != 1 || values.single().isBlank()) {
            throw InvalidQueryParameterException("Query parameter '$name' must have exactly one non-blank value")
        }
    }
}
