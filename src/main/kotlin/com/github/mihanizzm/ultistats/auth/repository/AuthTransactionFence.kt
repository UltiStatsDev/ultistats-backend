package com.github.mihanizzm.ultistats.auth.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection

/** The lock and protected effects live or die on the SAME original transaction. */
class AuthTransactionFence(private val jdbc:JdbcTemplate) {
    fun acquire(exclusive:Boolean) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "Authority fence requires the original transaction" }
        val source=jdbc.dataSource!!
        val connection=DataSourceUtils.getConnection(source)
        try {
            check(DataSourceUtils.isConnectionTransactional(connection,source)) { "Authority fence connection is not transaction-bound" }
            check(!connection.autoCommit) { "Authority fence cannot use autocommit" }
            check(connection.transactionIsolation==Connection.TRANSACTION_READ_COMMITTED) { "Authority fence requires READ_COMMITTED" }
            if(connection.metaData.databaseProductName=="PostgreSQL") {
                val resource="${AuthTransactionFence::class.java.name}:${System.identityHashCode(connection)}"
                val previous=TransactionSynchronizationManager.getResource(resource) as? Boolean
                check(previous!=false || !exclusive) { "Authority fence cannot upgrade shared to exclusive" }
                connection.createStatement().use { sql ->
                    sql.queryTimeout=12
                    val prior=sql.executeQuery("SHOW lock_timeout").use { rows -> rows.next();rows.getString(1) }
                    val priorMillis=sql.executeQuery("SELECT setting::bigint FROM pg_settings WHERE name='lock_timeout'").use { rows -> rows.next();rows.getLong(1) }
                    val bound=if(priorMillis>0) priorMillis.coerceAtMost(10000) else 10000
                    connection.prepareStatement("SELECT set_config('lock_timeout',?,true)").use { setting ->
                        setting.setString(1,"${bound}ms");setting.execute()
                    }
                    val function=if(exclusive) "pg_advisory_xact_lock" else "pg_advisory_xact_lock_shared"
                    sql.execute("SELECT $function(1598115923,152)")
                    // Only acquisition is bounded here; preserve the caller's domain SQL policy.
                    // A failed query aborts the original transaction, whose rollback restores SET LOCAL.
                    connection.prepareStatement("SELECT set_config('lock_timeout',?,true)").use { setting ->
                        setting.setString(1,prior);setting.execute()
                    }
                }
                // Transaction synchronization unbinds only our marker; PG releases the actual xact lock.
                if(previous==null) {
                    TransactionSynchronizationManager.bindResource(resource,exclusive)
                    TransactionSynchronizationManager.registerSynchronization(object:org.springframework.transaction.support.TransactionSynchronization {
                        override fun afterCompletion(status:Int) { TransactionSynchronizationManager.unbindResourceIfPossible(resource) }
                    })
                }
            } else {
                // Existing H2 unit fixtures serialize at the singleton; PG-only tests prove real lock semantics.
                jdbc.queryForObject("SELECT singleton FROM auth_installation WHERE singleton=1 FOR UPDATE",Int::class.java)
            }
        } finally { DataSourceUtils.releaseConnection(connection,source) }
    }
}
