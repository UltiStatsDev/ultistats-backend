package com.github.mihanizzm.ultistats.telemetry

import io.sentry.*
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** Real SDK envelopes, serialized at the last boundary; never constructs an HTTP transport. */
class SentryCapture : ITransportFactory {
    val envelopes = CopyOnWriteArrayList<String>()
    var creations = 0
    override fun create(options: SentryOptions, details: RequestDetails): ITransport {
        creations++
        return object : ITransport {
            override fun send(envelope: SentryEnvelope, hint: Hint) {
                val bytes = ByteArrayOutputStream()
                options.serializer.serialize(envelope, bytes)
                envelopes += bytes.toString(Charsets.UTF_8)
            }
            override fun flush(timeoutMillis: Long) = Unit
            override fun getRateLimiter(): RateLimiter? = null
            override fun close() = Unit
            override fun close(isRestarting: Boolean) = Unit
        }
    }
    fun flush(): List<String> {
        Sentry.flush(5_000)
        return envelopes.toList()
    }
    fun events() = flush().filter { it.contains("\"type\":\"event\"") }
    fun logs() = flush().filter { it.contains("\"type\":\"log\"") }
}

@TestConfiguration(proxyBeanMethods = false)
class SentryCaptureConfiguration {
    @Bean fun sentryCapture() = SentryCapture()
}
