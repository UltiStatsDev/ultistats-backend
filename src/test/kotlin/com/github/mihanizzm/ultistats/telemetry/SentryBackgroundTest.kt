package com.github.mihanizzm.ultistats.telemetry

import com.github.mihanizzm.ultistats.auth.repository.AuthStore
import com.github.mihanizzm.ultistats.auth.service.AuthAuditMaintenance
import io.sentry.Sentry
import io.sentry.spring.boot.jakarta.SentryAutoConfiguration
import io.sentry.spring.boot.jakarta.SentryLogbackAppenderAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SentryBackgroundTest {
    private fun context(vararg properties: String, verify: (SentryCapture, BackgroundAsyncFixture) -> Unit) {
        Sentry.close()
        try {
            ApplicationContextRunner()
                .withInitializer(ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=file:src/main/resources/application.yaml",
                    "sentry.environment=synthetic-background", "sentry.release=ultistats-backend@synthetic187")
                .withConfiguration(AutoConfigurations.of(SentryAutoConfiguration::class.java,
                    SentryLogbackAppenderAutoConfiguration::class.java))
                .withUserConfiguration(SentryCaptureConfiguration::class.java,
                    SentryPrivacyConfiguration::class.java, BackgroundAsyncConfiguration::class.java)
                .withPropertyValues(*properties)
                .run { ctx ->
                    assertThat(ctx).hasNotFailed()
                    verify(ctx.getBean(SentryCapture::class.java), ctx.getBean(BackgroundAsyncFixture::class.java))
                }
        } finally { Sentry.close() }
    }

    private fun witness(capture: SentryCapture) {
        LoggerFactory.getLogger(javaClass).error("Synthetic SDK witness", IllegalStateException("synthetic-witness-secret"))
        assertThat(capture.creations).isEqualTo(1)
        assertThat(capture.events()).hasSize(1)
        capture.envelopes.clear()
    }

    private fun auditFailure() {
        val store = Mockito.mock(AuthStore::class.java)
        val clock = Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), java.time.ZoneOffset.UTC)
        val calls = AtomicInteger()
        val nextTick = CountDownLatch(1)
        Mockito.`when`(store.pruneAudit(clock.instant().minus(Duration.ofDays(30)), 1000)).thenAnswer {
            if (calls.incrementAndGet() == 1) throw IllegalStateException("synthetic-audit-secret")
            nextTick.countDown()
            0
        }
        val maintenance = AuthAuditMaintenance(store, clock, 30)
        val scheduler = ThreadPoolTaskScheduler().apply {
            setThreadNamePrefix("sentry187-scheduled-")
            setAwaitTerminationSeconds(5)
            initialize()
        }
        try {
            val task = scheduler.scheduleWithFixedDelay(maintenance::clean, Duration.ofMillis(20))
            assertThat(nextTick.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(calls.get()).isGreaterThanOrEqualTo(2)
            assertThat(task.isDone).isFalse()
            task.cancel(false)
        } finally { scheduler.destroy() }
    }

    private fun issue(capture: SentryCapture, name: String, frame: String) {
        val envelopes = capture.flush()
        val directory = Path.of("build", "sentry-background-proof", name)
        Files.createDirectories(directory)
        envelopes.forEachIndexed { index, value -> Files.writeString(directory.resolve("envelope-$index.txt"), value) }
        assertThat(capture.events()).hasSize(1)
        assertThat(capture.events().single()).contains("IllegalStateException", "stacktrace", frame,
            "synthetic-background", "ultistats-backend@synthetic187", "ultistats-backend")
        assertThat(capture.logs()).isNotEmpty()
        assertThat(envelopes.joinToString()).doesNotContain("synthetic-audit-secret", "synthetic-async-secret",
            "synthetic-future-secret", "synthetic-witness-secret")
    }

    @Test fun `audit cleanup failure is reported once and the next repeating tick runs`() {
        context("sentry.dsn=http://synthetic@localhost/187") { capture, _ ->
            witness(capture)
            auditFailure()
            issue(capture, "scheduled", "AuthAuditMaintenance")
        }
    }

    @Test fun `default void async failure is reported while Future failure remains caller handled`() {
        context("sentry.dsn=http://synthetic@localhost/187") { capture, async ->
            witness(capture)
            assertThatThrownBy { async.futureFailure().get(5, TimeUnit.SECONDS) }
                .isInstanceOf(ExecutionException::class.java).hasCauseInstanceOf(IllegalStateException::class.java)
            assertThat(capture.events()).isEmpty()
            async.voidFailure()
            // Same single-thread executor: the barrier completes after default exception handling.
            async.barrier().get(5, TimeUnit.SECONDS)
            issue(capture, "async", "voidFailure")
        }
    }

    @Test fun `missing and empty DSN keep actual background failures transport free`() {
        for (properties in listOf(emptyArray(), arrayOf("sentry.dsn="))) {
            context(*properties) { capture, async ->
                auditFailure()
                async.voidFailure()
                async.barrier().get(5, TimeUnit.SECONDS)
                assertThat(Sentry.isEnabled()).isFalse()
                assertThat(capture.creations).isZero()
                assertThat(capture.flush()).isEmpty()
            }
        }
    }

    @Test fun `unrelated framework errors are not forwarded`() {
        context("sentry.dsn=http://synthetic@localhost/187") { capture, _ ->
            witness(capture)
            LoggerFactory.getLogger("org.springframework.unrelated.SyntheticFixture")
                .error("Synthetic unrelated error", IllegalStateException("synthetic-unrelated-secret"))
            assertThat(capture.flush()).isEmpty()
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableAsync
class BackgroundAsyncConfiguration {
    @Bean fun taskExecutor() = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 1
        setThreadNamePrefix("sentry187-async-")
        setAwaitTerminationSeconds(5)
    }
    @Bean fun backgroundAsyncFixture() = BackgroundAsyncFixture()
}

open class BackgroundAsyncFixture {
    @Async open fun voidFailure() { throw IllegalStateException("synthetic-async-secret") }
    @Async open fun futureFailure(): CompletableFuture<Unit> = throw IllegalStateException("synthetic-future-secret")
    @Async open fun barrier(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
}
