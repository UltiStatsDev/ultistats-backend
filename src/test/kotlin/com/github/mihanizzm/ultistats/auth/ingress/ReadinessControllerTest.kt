package com.github.mihanizzm.ultistats.auth.ingress

import org.junit.jupiter.api.Test
import org.springframework.boot.availability.ApplicationAvailabilityBean
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ReadinessControllerTest {
    @Test
    fun `ready migrated application and live database return minimal UP`() {
        val availability = available()
        val dataSource = DriverManagerDataSource("jdbc:h2:mem:auth154-ready;DB_CLOSE_DELAY=-1", "sa", "")
        val mvc = MockMvcBuilders.standaloneSetup(ReadinessController(dataSource, availability)).build()
        mvc.perform(get("/health/ready"))
            .andExpect(status().isOk)
            .andExpect(content().json("""{"status":"UP"}""", true))
            .andExpect(header().string("Cache-Control", "no-store"))
    }

    @Test
    fun `database failure returns only DOWN without exception or credentials`() {
        val dataSource = DriverManagerDataSource("jdbc:unavailable:private-host/secret-db", "private-user", "private-password")
        val mvc = MockMvcBuilders.standaloneSetup(ReadinessController(dataSource, available())).build()
        mvc.perform(get("/health/ready"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(content().json("""{"status":"DOWN"}""", true))
    }

    @Test
    fun `not yet ready application cannot report UP merely because DB connects`() {
        val dataSource = DriverManagerDataSource("jdbc:h2:mem:auth154-starting", "sa", "")
        val mvc = MockMvcBuilders.standaloneSetup(ReadinessController(dataSource, ApplicationAvailabilityBean())).build()
        mvc.perform(get("/health/ready"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(content().json("""{"status":"DOWN"}""", true))
    }

    private fun available() = ApplicationAvailabilityBean().apply {
        onApplicationEvent(AvailabilityChangeEvent(this, ReadinessState.ACCEPTING_TRAFFIC))
    }
}
