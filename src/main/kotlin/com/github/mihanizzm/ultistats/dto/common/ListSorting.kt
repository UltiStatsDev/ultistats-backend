package com.github.mihanizzm.ultistats.dto.common

interface SortField {
    val field: String
}

class InvalidSortException(message: String) : IllegalArgumentException(message)

/** Resolve the public query contract to the existing facade sorting representation. */
fun resolveSort(
    sortBy: SortField?,
    sortDirection: SortDirection?,
    sort: String?,
    defaultField: SortField,
    allowedFields: List<SortField>,
    parameters: Map<String, Array<String>>,
): SortParam {
    val names = setOf("sortBy", "sortDirection", "sort")
    // Form-binding aliases are not part of this scalar query API.
    for (key in parameters.keys) {
        val name = key.removePrefix("!").removePrefix("_").substringBefore('[')
        if (name in names && key != name) {
            throw InvalidSortException("Invalid '$name' parameter name. Use '$name' without prefixes or brackets")
        }
    }
    // Inspect raw values too: Spring converts empty enums to null and can collapse repeated values.
    for (name in names) {
        val values = parameters[name] ?: continue
        if (values.size != 1 || values.single().isBlank()) {
            throw InvalidSortException("Query parameter '$name' must have exactly one non-blank value")
        }
    }
    if ("sort" in parameters && ("sortBy" in parameters || "sortDirection" in parameters)) {
        throw InvalidSortException("Use either 'sort' or 'sortBy'/'sortDirection', not both")
    }
    if (sort == null) return SortParam((sortBy ?: defaultField).field, sortDirection ?: SortDirection.ASC)

    val parsed = try {
        SortParam.parse(sort)
    } catch (_: IllegalArgumentException) {
        throw InvalidSortException("Invalid 'sort': use field:asc or field:desc (direction is optional)")
    }
    if (allowedFields.none { it.field == parsed.field }) {
        throw InvalidSortException("Invalid 'sort' field. Allowed fields: ${allowedFields.joinToString { it.field }}")
    }
    return parsed
}
