package com.github.mihanizzm.ultistats.telemetry

import io.sentry.Sentry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath

@SpringBootTest(properties = ["sentry.dsn="])
@AutoConfigureMockMvc
@Import(SentryCaptureConfiguration::class)
@DirtiesContext
class SentryEmptyDsnStartupTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var capture: SentryCapture

    @Test fun `empty DSN keeps real application and auth ingress operational without transport`() {
        mvc.perform(get("/api/v1/players")).andExpect(status().isOk)
        mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("PUBLIC_TRANSPORT_READ_ONLY"))
        assertThat(Sentry.isEnabled()).isFalse()
        assertThat(capture.creations).isZero()
        assertThat(capture.flush()).isEmpty()
    }
}
