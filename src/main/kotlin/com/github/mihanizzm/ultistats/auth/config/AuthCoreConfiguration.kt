package com.github.mihanizzm.ultistats.auth.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

@Configuration
class AuthCoreConfiguration {
    @Bean fun authClock(): Clock = Clock.systemUTC()
    @Bean fun authPasswordEncoder(): PasswordEncoder = Argon2PasswordEncoder(16,32,1,19456,2)
    @Bean fun authTransactions(manager: PlatformTransactionManager) = TransactionTemplate(manager)
}
