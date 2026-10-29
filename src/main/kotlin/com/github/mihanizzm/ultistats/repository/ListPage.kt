package com.github.mihanizzm.ultistats.repository

import com.github.mihanizzm.ultistats.dto.common.SortParam

data class ListPageRequest(val page: Int, val size: Int, val sort: SortParam) {
    init {
        require(page >= 0) { "Page must not be negative" }
        require(size > 0) { "Size must be positive" }
    }

    val offset: Long get() = page.toLong() * size
}

data class ListPage<T>(val content: List<T>, val totalElements: Long)
