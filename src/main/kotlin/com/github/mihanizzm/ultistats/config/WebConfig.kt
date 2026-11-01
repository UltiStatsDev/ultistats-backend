package com.github.mihanizzm.ultistats.config

import com.github.mihanizzm.ultistats.auth.ingress.AuthOriginConfiguration
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Bean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.web.filter.CorsFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.web.servlet.handler.HandlerMappingIntrospector
import com.github.mihanizzm.ultistats.auth.security.AuthCorsProcessor
import com.github.mihanizzm.ultistats.auth.security.AuthProblemWriter
import com.github.mihanizzm.ultistats.auth.security.AuthEntryClassifier
import com.github.mihanizzm.ultistats.auth.security.AuthSurfacePolicy
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(
    private val storageProperties: StorageProperties,
    private val origins: AuthOriginConfiguration,
) : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins(*origins.secureOrigins().toTypedArray())
            .allowCredentials(true)
            .allowedMethods("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("Content-Type", "Accept", "X-CSRF-TOKEN",
                "X-UltiStats-Installation-Id", "X-UltiStats-Account-Id", "X-UltiStats-Session-Id")
            .maxAge(3600)
    }

    // Spring Security uses this named filter instead of constructing its plaintext default.
    @Bean("corsFilter")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    fun authCorsFilter(
        @Qualifier("mvcHandlerMappingIntrospector") source: HandlerMappingIntrospector,
        writer: AuthProblemWriter,
        classifiers: ObjectProvider<AuthEntryClassifier>,
        policies: ObjectProvider<AuthSurfacePolicy>,
    ): CorsFilter = CorsFilter(source).apply {
        setCorsProcessor(AuthCorsProcessor(writer, classifiers.ifAvailable, policies.ifAvailable))
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    fun authCorsFilterRegistration(@Qualifier("corsFilter") filter: CorsFilter): FilterRegistrationBean<CorsFilter> =
        FilterRegistrationBean(filter).apply { isEnabled = false } // Security chain owns the only invocation.

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry
            .addResourceHandler("/uploads/**")
            .addResourceLocations(storageProperties.resourceLocation())
    }
}
