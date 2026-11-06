package com.github.mihanizzm.ultistats.telemetry

import com.github.mihanizzm.ultistats.fixture.LegacyDomainHttpSecurity
import com.github.mihanizzm.ultistats.service.FileStorageException
import io.sentry.*
import io.sentry.protocol.Request
import io.sentry.protocol.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.*
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.security.access.AccessDeniedException
import com.github.mihanizzm.ultistats.service.FileStorageService
import com.github.mihanizzm.ultistats.service.PhotoLifecycleService
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.InvalidFileUploadException
import com.github.mihanizzm.ultistats.realtime.*
import com.github.mihanizzm.ultistats.model.Match
import io.sentry.logger.SentryLogParameters
import org.apache.catalina.connector.ClientAbortException
import org.springframework.test.annotation.DirtiesContext
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.mockito.Mockito
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.nio.file.Path
import java.nio.file.Files

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.config.additional-location=file:src/main/resources/application.yaml",
    "spring.datasource.url=jdbc:h2:mem:sentry185;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=none",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "spring.flyway.enabled=false",
    "sentry.dsn=http://synthetic@localhost/185", "sentry.environment=synthetic-test",
    "sentry.release=ultistats-backend@synthetic185", "sentry.tags.service=ultistats-backend",
])
@DirtiesContext
@Import(SentryCaptureConfiguration::class, TelemetryEndpoints::class, LegacyDomainHttpSecurity::class)
class SentryApplicationIntegrationTest {
    @Autowired lateinit var capture: SentryCapture
    @Autowired lateinit var http: TestRestTemplate
    private val log = LoggerFactory.getLogger(javaClass)

    @AfterEach fun retainSyntheticEnvelopes(info: TestInfo) {
        val directory = Path.of("build", "sentry-proof", info.testMethod.get().name.replace(Regex("[^a-zA-Z0-9]"), "-"))
        Files.createDirectories(directory)
        capture.flush().forEachIndexed { index, envelope ->
            Files.writeString(directory.resolve("envelope-$index.txt"), envelope)
        }
    }

    @BeforeEach fun clear() {
        capture.flush()
        capture.envelopes.clear()
        Sentry.configureScope { it.clear() }
    }

    @Test fun `unhandled HTTP 500 has exactly one issue with stack and configured context`() {
        val headers = HttpHeaders().apply {
            set("X-Legacy-Domain-Test", "domain-contract")
            set("Authorization", "Bearer synthetic-auth-secret")
            set("Cookie", "session=synthetic-session-secret")
        }
        val response = http.exchange("/__telemetry/failure?password=synthetic-query-secret",
            HttpMethod.POST, HttpEntity("password=synthetic-body-secret", headers), String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        val event = capture.events().single()
        assertThat(event).contains("stacktrace", "IllegalStateException", "synthetic-test",
            "ultistats-backend@synthetic185", "ultistats-backend", "POST", "/__telemetry/failure")
        assertThat(event).doesNotContain("synthetic-auth-secret", "synthetic-session-secret",
            "synthetic-query-secret", "synthetic-body-secret", "synthetic-exception-secret")
    }

    @Test fun `ERROR produces issue and WARN produces Logs without issue`() {
        log.warn("Synthetic warning {}", "synthetic-param-secret")
        assertThat(capture.events()).isEmpty()
        assertThat(capture.logs().joinToString()).contains("Synthetic warning")
            .contains("synthetic-test", "ultistats-backend@synthetic185", "ultistats-backend")
            .doesNotContain("synthetic-param-secret")
        capture.envelopes.clear()
        log.error("Synthetic infrastructure failure", IllegalStateException("synthetic-error-secret"))
        assertThat(capture.events()).hasSize(1)
        assertThat(capture.events().single()).contains("stacktrace", "Synthetic infrastructure failure")
            .doesNotContain("synthetic-error-secret")
    }

    @Test fun `handled validation auth and disconnected SSE do not create issues`() {
        val headers = HttpHeaders().apply { set("X-Legacy-Domain-Test", "domain-contract") }
        assertThat(http.exchange("/__telemetry/invalid", HttpMethod.GET,
            HttpEntity<Void>(headers), String::class.java).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(http.postForEntity("/api/v1/auth/login", mapOf("password" to "synthetic-login-secret"),
            String::class.java).statusCode.is4xxClientError).isTrue()
        log.error("Disconnected stream", AsyncRequestNotUsableException("synthetic-disconnect-secret"))
        log.error("Denied", AccessDeniedException("synthetic-denied-secret"))
        log.error("Client aborted", ClientAbortException(IOException("synthetic-abort-secret")))
        val invalidLogin = http.exchange("/api/v1/auth/login", HttpMethod.POST,
            HttpEntity(mapOf("password" to "synthetic-validation-secret"), headers), String::class.java)
        assertThat(invalidLogin.statusCode.is4xxClientError).isTrue()
        assertThat(capture.events()).isEmpty()
    }

    @Test fun `actual SSE delivery disconnect stays benign through the realtime listener`() {
        val matches = Mockito.mock(MatchService::class.java)
        val matchId = UUID.fromString("00000000-0000-0000-0000-000000000186")
        Mockito.`when`(matches.get(matchId)).thenReturn(
            Match(matchId, emptyList(), startedAt = Instant.EPOCH))
        val connection = object : MatchEventConnection {
            override val emitter = SseEmitter()
            var disconnected = false
            override fun send(name: String, data: Any, reconnectTimeMs: Long?) {
                if (disconnected) throw ClientAbortException(IOException("synthetic-sse-secret"))
            }
            override fun comment(value: String) = Unit
            override fun complete() = Unit
            override fun completeWithError(cause: Throwable) = Unit
            override fun onCompletion(callback: () -> Unit) = Unit
            override fun onTimeout(callback: () -> Unit) = Unit
            override fun onError(callback: (Throwable) -> Unit) = Unit
        }
        val stream = MatchEventStreamService(matches,
            MatchEventConnectionFactory { connection },
            MatchRealtimeProperties())
        assertThat(stream.subscribe(matchId)).isInstanceOf(MatchEventStreamResult.Opened::class.java)
        connection.disconnected = true
        MatchRealtimeSignalListener(stream)
            .onMatchFinished(MatchFinishedSignal(matchId))
        assertThat(capture.events()).isEmpty()
    }

    @Test fun `storage 503 is reported with throwable while retaining response contract`() {
        val headers = HttpHeaders().apply { set("X-Legacy-Domain-Test", "domain-contract") }
        val response = http.exchange("/__telemetry/storage", HttpMethod.GET,
            HttpEntity<Void>(headers), String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(response.body).contains("FILE_STORAGE_ERROR")
        assertThat(capture.events()).hasSize(1)
        assertThat(capture.events().single()).contains("FileStorageException", "stacktrace")
            .doesNotContain("synthetic-storage-secret")
    }

    @Test fun `swallowed cleanup reports an issue without changing committed domain outcome`() {
        val storage = Mockito.mock(FileStorageService::class.java)
        Mockito.doThrow(IllegalStateException("synthetic-cleanup-secret"))
            .`when`(storage).delete("/uploads/synthetic.png")
        var removed = false
        PhotoLifecycleService(storage)
            .delete("/uploads/synthetic.png") { removed = it == null }
        assertThat(removed).isTrue()
        assertThat(capture.events()).hasSize(1)
        assertThat(capture.events().single()).contains("stacktrace", "Photo completion cleanup failed")
            .doesNotContain("synthetic-cleanup-secret", "/uploads/synthetic.png")
    }

    @Test fun `unexpected realtime listener failure reports issue with bounded match context`() {
        val stream = Mockito.mock(MatchEventStreamService::class.java)
        val matchId = UUID.fromString("00000000-0000-0000-0000-000000000185")
        Mockito.doThrow(IllegalStateException("synthetic-realtime-secret"))
            .`when`(stream).finishMatch(matchId)
        MatchRealtimeSignalListener(stream)
            .onMatchFinished(MatchFinishedSignal(matchId))
        assertThat(capture.events()).hasSize(1)
        assertThat(capture.events().single()).contains("stacktrace", matchId.toString(), "matchId")
            .doesNotContain("synthetic-realtime-secret")
        assertThat(MDC.get("matchId")).isNull()
    }

    @Test fun `direct structured logs discard user attributes credentials and SDK parameters`() {
        Sentry.configureScope { scope ->
            scope.user = User().apply { id = "synthetic-user-secret"; email = "synthetic-log-email-secret" }
        }
        val attributes = SentryAttributes.of(
            SentryAttribute.stringAttribute("Authorization", "synthetic-log-header-secret"),
            SentryAttribute.stringAttribute("session", "synthetic-log-session-secret"),
            SentryAttribute.stringAttribute("custom", "synthetic-custom-secret"),
        )
        Sentry.logger().log(SentryLogLevel.WARN, SentryLogParameters.create(attributes),
            "Synthetic structured value %s", "synthetic-structured-param-secret")
        Sentry.logger().warn("password=synthetic-inline-secret Bearer synthetic-bearer-secret")
        assertThat(capture.events()).isEmpty()
        assertThat(capture.logs().joinToString()).contains("Synthetic structured value", "[Filtered]")
            .doesNotContain("synthetic-log-header-secret", "synthetic-log-session-secret", "synthetic-custom-secret",
                "synthetic-structured-param-secret", "synthetic-inline-secret", "synthetic-bearer-secret",
                "synthetic-user-secret", "synthetic-log-email-secret")
    }

    @Test fun `serialized events and structured logs exclude untrusted attributes and parameters`() {
        val event = SentryEvent(IllegalStateException("synthetic-throwable-secret")).apply {
            request = Request().apply {
                url = "http://localhost/api/v1/auth/login"
                method = "POST"
                headers = mutableMapOf("Authorization" to "synthetic-header-secret",
                    "Cookie" to "synthetic-cookie-secret", "Set-Cookie" to "synthetic-response-cookie-secret")
                cookies = "synthetic-request-cookie-secret"
                data = mapOf("password" to "synthetic-password-secret")
                queryString = "token=synthetic-query-secret"
                envs = mutableMapOf("session" to "synthetic-env-secret")
                others = mutableMapOf("credential" to "synthetic-other-secret")
            }
            user = User().apply { email = "synthetic-email-secret"; ipAddress = "synthetic-ip-secret" }
            setExtra("session", "synthetic-extra-secret")
            setTag("credential", "synthetic-tag-secret")
        }
        MDC.put("password", "synthetic-mdc-secret")
        try {
            log.warn("Synthetic credentials {}", "synthetic-argument-secret")
            Sentry.captureEvent(event)
        } finally { MDC.clear() }
        val payload = capture.flush().joinToString()
        assertThat(capture.events()).hasSize(1)
        assertThat(capture.logs()).isNotEmpty()
        assertThat(payload).contains("IllegalStateException", "stacktrace", "Synthetic credentials")
        assertThat(payload).doesNotContain("synthetic-throwable-secret", "synthetic-header-secret",
            "synthetic-cookie-secret", "synthetic-response-cookie-secret", "synthetic-request-cookie-secret",
            "synthetic-password-secret", "synthetic-query-secret", "synthetic-email-secret",
            "synthetic-ip-secret", "synthetic-extra-secret", "synthetic-tag-secret",
            "synthetic-mdc-secret", "synthetic-argument-secret", "synthetic-env-secret", "synthetic-other-secret")
    }
}

@TestConfiguration(proxyBeanMethods = false)
class TelemetryEndpoints {
    @Bean fun telemetryEndpoint() = TelemetryEndpoint()
}

@org.springframework.boot.test.context.TestComponent
@RestController
class TelemetryEndpoint {
    @PostMapping("/__telemetry/failure") fun failure(): String =
        throw IllegalStateException("synthetic-exception-secret")
    @GetMapping("/__telemetry/invalid") fun invalid(): String =
        throw InvalidFileUploadException("Synthetic invalid upload")
    @GetMapping("/__telemetry/storage") fun storage(): String = throw FileStorageException("synthetic-storage-secret")
}
