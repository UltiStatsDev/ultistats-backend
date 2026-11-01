package com.github.mihanizzm.ultistats.auth.service

import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Expiring process-local windows, bounded without evicting a live throttle. */
class AuthRateLimiter(private val clock:Clock,private val maxEntries:Int=10000) {
    private data class Window(val expires:Instant,var count:Int=0)
    private val windows=HashMap<String,Window>()
    @Synchronized fun check(key:String,limit:Int,period:Duration) {
        val now=clock.instant()
        windows.entries.removeIf { !now.isBefore(it.value.expires) }
        val window=windows[key] ?: run {
            if(windows.size>=maxEntries) throw AuthProblem(429,"AUTH_RATE_LIMITED",60)
            Window(now.plus(period)).also { windows[key]=it }
        }
        if(window.count>=limit) throw AuthProblem(429,"AUTH_RATE_LIMITED",Duration.between(now,window.expires).seconds.coerceAtLeast(1))
        window.count++
    }
}
