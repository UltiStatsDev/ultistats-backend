package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.service.EventService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.UUID

/** Check the negotiated journal format before a command body is decoded. */
@Target(AnnotationTarget.FUNCTION)
annotation class PointResultCommand(val matchVariable: String = "matchId", val batch: Boolean = false)

@Configuration(proxyBeanMethods = false)
class PointResultCommandConfiguration(private val events: ObjectProvider<EventService>) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(object : HandlerInterceptor {
            override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
                val command = (handler as? HandlerMethod)?.getMethodAnnotation(PointResultCommand::class.java) ?: return true
                val client = request.pointLineupClient()
                if (client.supportsPointResults) return true
                val variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<*, *>
                val matchId = (variables?.get(command.matchVariable) as? String)?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                }
                if (matchId != null) events.getObject().requirePointResultCommandClient(matchId, client, command.batch)
                return true
            }
        })
    }
}
