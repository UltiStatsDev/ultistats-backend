package com.github.mihanizzm.ultistats.csvimport

import com.github.mihanizzm.ultistats.controller.CsvImportController
import com.github.mihanizzm.ultistats.service.CsvImportService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockMultipartHttpServletRequest
import kotlin.test.assertEquals

class CsvImportFlagTest {
    @Test fun `missing flag returns 404 before reading any input or calling service`() {
        val parser = mock(CsvImportParser::class.java)
        val service = mock(CsvImportService::class.java)
        val controller = CsvImportController(parser, service, MockEnvironment())
        assertEquals(404, controller.create(MockMultipartHttpServletRequest()).statusCode.value())
        verifyNoInteractions(parser, service)
    }
}
