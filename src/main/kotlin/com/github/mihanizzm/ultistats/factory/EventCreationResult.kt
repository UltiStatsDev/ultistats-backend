package com.github.mihanizzm.ultistats.factory

import com.github.mihanizzm.ultistats.model.events.Event

sealed interface EventCreationResult {
    data class Success(val event: Event) : EventCreationResult
    data class Invalid(val detail: String) : EventCreationResult
}
