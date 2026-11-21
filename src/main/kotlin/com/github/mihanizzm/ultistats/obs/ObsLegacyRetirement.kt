package com.github.mihanizzm.ultistats.obs

import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

/** Literal response even for Accept: text/event-stream; no emitter or domain read. */
internal fun retiredObsStream(): ResponseEntity<String> = ResponseEntity.status(HttpStatus.GONE)
    .cacheControl(CacheControl.noStore()).contentType(MediaType.TEXT_PLAIN)
    .body("OBS stream retired. Copy a protected source URL from /obs-admin.html.")
