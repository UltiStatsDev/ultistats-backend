package com.github.mihanizzm.ultistats.obs

import org.springframework.boot.test.context.TestComponent
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Standalone MVC only: preserves publisher AFTER_COMMIT tests without exposing retired production routes. */
@TestComponent
@RestController
class ObsInternalStreamProbe(private val config: ObsConfigStream, private val notifications: ObsNotificationStream) {
    @GetMapping("/test-internal/config", produces = ["text/event-stream"])
    fun config() = config.subscribe()
    @GetMapping("/test-internal/notifications", produces = ["text/event-stream"])
    fun notifications() = notifications.subscribe()

    fun open(path: String): MvcResult = MockMvcBuilders.standaloneSetup(this).build()
        .perform(get("/test-internal/$path")).andReturn()
}
