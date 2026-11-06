package com.github.mihanizzm.ultistats.telemetry

import io.sentry.Sentry
import io.sentry.spring.boot.jakarta.SentryAutoConfiguration
import io.sentry.spring.boot.jakarta.SentryLogbackAppenderAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class SentryDisabledTest {
    @Test fun `missing and empty DSN never construct transport or enable Sentry`() {
        for (properties in listOf(emptyArray(), arrayOf("sentry.dsn="))) {
            Sentry.close()
            ApplicationContextRunner()
                .withInitializer(org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=file:src/main/resources/application.yaml")
                .withConfiguration(AutoConfigurations.of(SentryAutoConfiguration::class.java,
                    SentryLogbackAppenderAutoConfiguration::class.java))
                .withUserConfiguration(SentryCaptureConfiguration::class.java)
                .withPropertyValues(*properties)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    LoggerFactory.getLogger(javaClass).error("Synthetic disabled failure")
                    Sentry.captureException(IllegalStateException("synthetic-disabled-secret"))
                    val capture = context.getBean(SentryCapture::class.java)
                    assertThat(Sentry.isEnabled()).isFalse()
                    assertThat(capture.creations).isZero()
                    assertThat(capture.flush()).isEmpty()
                }
            Sentry.close()
        }
    }
}
