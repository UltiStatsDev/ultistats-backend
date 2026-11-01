package com.github.mihanizzm.ultistats.config

import com.github.mihanizzm.ultistats.auth.ingress.AuthOriginConfiguration
import org.springframework.context.annotation.Configuration
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

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry
            .addResourceHandler("/uploads/**")
            .addResourceLocations(storageProperties.resourceLocation())
    }
}
