package com.github.mihanizzm.ultistats.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.mihanizzm.ultistats.auth.security.AuthSurfacePolicyImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.mock.web.MockServletContext
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.support.GenericWebApplicationContext
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.nio.file.Path

/** Compare real Spring annotation mapping resolution to the reviewed inventory, not a second regex. */
class AuthMappingInventoryTest {
    @Test
    fun `every actual application handler has a versioned reviewed inventory entry`() {
        val scanner = ClassPathScanningCandidateComponentProvider(false).apply {
            addIncludeFilter(AnnotationTypeFilter(RestController::class.java))
        }
        GenericWebApplicationContext().use { context ->
            context.servletContext = MockServletContext()
            scanner.findCandidateComponents("com.github.mihanizzm.ultistats").forEach { bean ->
                val type = Class.forName(bean.beanClassName)
                if (type.protectionDomain.codeSource.location != AuthSurfacePolicyImpl::class.java.protectionDomain.codeSource.location) return@forEach
                val constructor = type.constructors.minBy { it.parameterCount }
                // Handler metadata comes from the actual controller. Domain dependencies are not invoked.
                val instance = constructor.newInstance(*constructor.parameterTypes.map { Mockito.mock(it) }.toTypedArray())
                context.beanFactory.registerSingleton(type.name, instance)
            }
            context.refresh()
            val mapping = RequestMappingHandlerMapping().apply { setApplicationContext(context); afterPropertiesSet() }
            val actual = mapping.handlerMethods.keys.flatMap { info ->
                val paths = info.pathPatternsCondition?.patternValues ?: info.patternsCondition!!.patterns
                info.methodsCondition.methods.flatMap { method -> paths.map { "${method.name} $it" } }
            }.toSet()
            val inventory = jacksonObjectMapper().readTree(Path.of("docs/api/auth-surfaces-v1.json").toFile())
            val reviewed = inventory["routes"].map { "${it["method"].asText()} ${it["path"].asText()}" }.toSet()
            assertEquals(reviewed, actual, "New, alternate or removed application mappings must be explicitly reviewed")
        }
    }
}
