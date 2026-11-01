package com.github.mihanizzm.ultistats.auth.controller

import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Retains springdoc's resource assets while supplying the shared secure request adapter. */
@RestController
class StaffToolsController {
    @GetMapping("/swagger-ui/index.html", produces = [MediaType.TEXT_HTML_VALUE])
    fun swagger(): ResponseEntity<Resource> = ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .cacheControl(CacheControl.noStore())
        .body(ClassPathResource("static/auth/swagger.html"))
}
