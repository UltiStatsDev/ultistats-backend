package com.github.mihanizzm.ultistats.repository.jpa

import com.github.mihanizzm.ultistats.model.EventEntity

interface EventInsertRepository {
    fun insert(event: EventEntity): EventEntity
}
