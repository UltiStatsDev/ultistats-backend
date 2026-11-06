package com.github.mihanizzm.ultistats.telemetry

import ch.qos.logback.classic.spi.ILoggingEvent
import io.sentry.SentryOptions
import io.sentry.SentryLogEventAttributeValue
import org.springframework.beans.factory.annotation.Value
import io.sentry.TypeCheckHint
import io.sentry.protocol.Request
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.util.UUID

/** Outbound SDK boundary only. Console logs retain their existing formatting. */
@Configuration(proxyBeanMethods = false)
class SentryPrivacyConfiguration(
    @Value("\${sentry.tags.service:ultistats-backend}")
    private val service: String,
) {
    @Bean
    fun sentryEventPrivacy() = SentryOptions.BeforeSendCallback { event, hint ->
        event.request = event.request?.let { original ->
            // Only URL without credentials/query/fragment and method cross this boundary.
            Request().apply {
                method = original.method
                url = original.url?.let { raw ->
                    runCatching { URI(raw).let { URI(it.scheme, null, it.host, it.port, it.path, null, null).toString() } }
                        .getOrNull()
                }
            }
        }
        event.user = null
        event.serverName = null
        event.breadcrumbs = null // free-form messages/data can carry request or login values
        event.extras = event.extras?.filter { (key, value) -> safeDomainId(key, value) }
        event.tags = event.tags?.filter { (key, value) -> safeDomainId(key, value) }
        event.setTag("service", service)
        event.contexts.clear()
        event.threads = null
        event.exceptions?.forEach { exception ->
            // Keep exception types, mechanisms and stacks, never arbitrary exception messages.
            exception.value = null
            exception.unknown = null
            exception.stacktrace?.frames?.forEach { frame -> frame.vars = null }
        }
        val logging = hint.get(TypeCheckHint.SENTRY_SYNTHETIC_EXCEPTION) as? ILoggingEvent
        logging?.keyValuePairs?.forEach { pair ->
            val value = pair.value?.toString()
            if (safeDomainId(pair.key, value)) event.setTag(pair.key, value)
        }
        event.message?.apply {
            formatted = safeMessage(logging?.message ?: message ?: "Application error")
            message = null
            params = null
            unknown = null
        }
        event.unknown = null
        event
    }

    @Bean
    fun sentryLogPrivacy() = SentryOptions.Logs.BeforeSendLogCallback { log ->
        // The Logback SDK includes the template separately when it formats arguments.
        // Keep that template; discard formatted parameters and all arbitrary MDC attributes.
        val template = log.attributes?.get("sentry.message.template")?.value as? String
        log.body = safeMessage(template ?: log.body)
        log.attributes = log.attributes?.filter { (key, value) ->
            key in logMetadata || safeDomainId(key.removePrefix("mdc."), value.value)
        }
        log.setAttribute("service", SentryLogEventAttributeValue("string", service))
        log.unknown = null
        log
    }

    private fun safeDomainId(key: String, value: Any?): Boolean =
        key in domainIds && value is String && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private fun safeMessage(message: String): String = credential.replace(bearer.replace(message, "Bearer [Filtered]")) {
        "${it.groupValues[1]}=[Filtered]"
    }

    companion object {
        private val domainIds = setOf("matchId", "eventId", "playerId", "teamId")
        private val logMetadata = setOf("sentry.environment", "sentry.release", "sentry.sdk.name",
            "sentry.sdk.version", "sentry.origin", "service")
        private val bearer = Regex("(?i)Bearer\\s+[^\\s,;]+")
        private val credential = Regex("(?i)(password|token|session(?:id)?|authorization|cookie|set-cookie|credential)\\s*[:=]\\s*[^\\s,;]+")
    }
}
