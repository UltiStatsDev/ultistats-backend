package com.github.mihanizzm.ultistats.auth

import com.github.mihanizzm.ultistats.auth.repository.AuthTransactionFence
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.time.Duration
import kotlin.test.*

@EnabledIfEnvironmentVariable(named="TEST_POSTGRES_URL",matches=".+")
class AuthFenceTimeoutPostgresTest {
    private val source=DriverManagerDataSource(System.getenv("TEST_POSTGRES_URL"),System.getenv("TEST_POSTGRES_USERNAME"),System.getenv("TEST_POSTGRES_PASSWORD"))
    private val jdbc=JdbcTemplate(source)
    private val transactions=TransactionTemplate(DataSourceTransactionManager(source))
    private val fence=AuthTransactionFence(jdbc)
    @Test fun `fence restores preexisting transaction timeout and repeated same mode never upgrades`() {
        transactions.execute {
            jdbc.execute("SET LOCAL lock_timeout='42ms'")
            fence.acquire(false)
            assertEquals("42ms",jdbc.queryForObject("SHOW lock_timeout",String::class.java))
            fence.acquire(false)
            assertEquals("42ms",jdbc.queryForObject("SHOW lock_timeout",String::class.java))
            assertFailsWith<IllegalStateException> { fence.acquire(true) }
        }
    }
    @Test fun `contended fence preserves stricter caller timeout and failed acquisition rolls back cleanly`() {
        DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),System.getenv("TEST_POSTGRES_USERNAME"),System.getenv("TEST_POSTGRES_PASSWORD")).use { holder ->
            holder.autoCommit=false
            holder.createStatement().execute("SELECT pg_advisory_xact_lock(1598115923,152)")
            val started=System.nanoTime()
            try {
                assertFails { transactions.execute { jdbc.execute("SET LOCAL lock_timeout='50ms'");fence.acquire(false) } }
                assertTrue(Duration.ofNanos(System.nanoTime()-started)<Duration.ofSeconds(2))
            } finally { holder.rollback() }
        }
        transactions.execute { assertEquals("0",jdbc.queryForObject("SHOW lock_timeout",String::class.java));fence.acquire(true) }
    }
}
