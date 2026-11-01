package com.github.mihanizzm.ultistats

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import com.github.mihanizzm.ultistats.auth.cli.OwnerAuthCli
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class UltistatsApplication

fun main(args: Array<String>) {
	if (args.firstOrNull() == "owner") {
		val command = OwnerAuthCli.parse(args.toList()) // Reject secret argv before application startup.
		SpringApplicationBuilder(UltistatsApplication::class.java).web(WebApplicationType.NONE)
			.run().use { context ->
				context.getBean(OwnerAuthCli::class.java).execute(command, { OwnerAuthCli.readPassword(command) }, ::println)
			}
		return
	}
	runApplication<UltistatsApplication>(*args)
}
