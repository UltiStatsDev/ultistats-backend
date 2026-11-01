package com.github.mihanizzm.ultistats.auth.ingress

import com.github.mihanizzm.ultistats.auth.model.AuthEntryMode
import org.apache.catalina.connector.Connector
import org.apache.catalina.valves.RemoteIpValve
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import java.net.InetAddress

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthIngressProperties::class)
class AuthConnectorConfiguration(private val properties: AuthIngressProperties, private val environment: Environment) {
    @Bean
    fun entryClassifier(): ListenerAuthEntryClassifier = ListenerAuthEntryClassifier(properties)

    @Bean
    fun connectorCustomizer(classifier: ListenerAuthEntryClassifier): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        object : WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {
            override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

            override fun customize(factory: TomcatServletWebServerFactory) {
                properties.validate()
                require(environment.getProperty("server.forward-headers-strategy", "none").equals("none", ignoreCase = true)) {
                    "forwarded header processing must be disabled; entry capability uses the original local socket"
                }
                require(factory.engineValves.none { it is RemoteIpValve }) { "RemoteIpValve is not permitted on auth ingress" }
                require(properties.loopbackMode || properties.privatePort == 0 || factory.port != properties.privatePort) {
                    "anonymous and private connector ports must differ"
                }
                factory.setAddress(InetAddress.getByName(properties.anonymousAddress))
                factory.addConnectorCustomizers({ connector ->
                    classifier.register(connector, if (properties.loopbackMode) AuthEntryMode.LOOPBACK else AuthEntryMode.ANONYMOUS)
                })
                if (!properties.loopbackMode) {
                    val connector = Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL).apply {
                        port = properties.privatePort
                        setProperty("address", "127.0.0.1")
                        // TLS terminates in the trusted local ingress. This socket
                        // is never published; headers cannot mark another socket secure.
                        scheme = "https"
                        secure = true
                    }
                    classifier.register(connector, AuthEntryMode.SECURE)
                    factory.addAdditionalTomcatConnectors(connector)
                }
            }
        }
}
