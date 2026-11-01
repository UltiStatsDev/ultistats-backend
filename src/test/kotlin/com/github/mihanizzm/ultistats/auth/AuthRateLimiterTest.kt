package com.github.mihanizzm.ultistats.auth

import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import com.github.mihanizzm.ultistats.auth.service.AuthRateLimiter
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.*

class AuthRateLimiterTest {
    @Test fun `sixth pair attempt is throttled with retry after and next window permits login`() {
        val clock=AuthTestClock()
        val limiter=AuthRateLimiter(clock)
        repeat(5) { limiter.check("source:login",5,Duration.ofMinutes(1)) }
        val denial=assertFailsWith<AuthProblem> { limiter.check("source:login",5,Duration.ofMinutes(1)) }
        assertEquals(429,denial.status)
        assertEquals(60,denial.retryAfterSeconds)
        limiter.check("other-source:login",5,Duration.ofMinutes(1))
        clock.now=clock.now.plusSeconds(60)
        limiter.check("source:login",5,Duration.ofMinutes(1))
    }
    @Test fun `live bucket saturation never evicts a limit and expired buckets release bounded storage`() {
        val clock=AuthTestClock()
        val limiter=AuthRateLimiter(clock,2)
        limiter.check("a",1,Duration.ofMinutes(1))
        limiter.check("b",1,Duration.ofMinutes(1))
        assertAuthProblem("AUTH_RATE_LIMITED") { limiter.check("c",1,Duration.ofMinutes(1)) }
        assertAuthProblem("AUTH_RATE_LIMITED") { limiter.check("a",1,Duration.ofMinutes(1)) }
        clock.now=clock.now.plusSeconds(60)
        limiter.check("c",1,Duration.ofMinutes(1))
    }
}
