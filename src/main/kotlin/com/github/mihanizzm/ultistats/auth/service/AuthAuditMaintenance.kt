package com.github.mihanizzm.ultistats.auth.service

import com.github.mihanizzm.ultistats.auth.repository.AuthStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

@Component
class AuthAuditMaintenance(private val store:AuthStore,private val clock:Clock,
    @Value("\${app.auth.audit-retention-days:30}") private val retentionDays:Long) {
    init { require(retentionDays>0) { "Audit retention must be positive" } }
    @Scheduled(fixedDelayString="\${app.auth.audit-cleanup-ms:3600000}",initialDelayString="\${app.auth.audit-cleanup-ms:3600000}")
    fun clean() {
        val before=clock.instant().minus(Duration.ofDays(retentionDays))
        repeat(10) { if(store.pruneAudit(before)<1000) return }
    }
}
