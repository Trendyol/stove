package com.trendyol.stove.testing.e2e.system

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*

class PropertiesFile() {

    companion object {
        const val reuseEnabled = "testcontainers.reuse.enable=true"
    }

    private val l: Logger = LoggerFactory.getLogger(javaClass)
    private val propertiesFile: Path = Paths.get(System.getProperty("user.home"), ".testcontainers.properties")

    fun detectAndLogStatus() {
        if (propertiesFile.exists()) {
            l.info("'.testcontainers.properties' file exists")
            when {
                propertiesFile.readText()
                    .contains(reuseEnabled) -> l.info("'.testcontainers.properties' looks good and contains reuse feature!")

                else -> l.info(
                    """
                    '.testcontainers.properties' does not contain 'testcontainers.reuse.enable=true'
                     | You need to create either by yourself or using '${TestSystemOptionsDsl::enableReuseForTestContainers.name}' method
                    """.trimIndent()
                )
            }
        } else {
            l.info(
                """'.testcontainers.properties' file DOES NOT exist. 
                    |You need to create either by yourself or using '${TestSystemOptionsDsl::enableReuseForTestContainers.name} method
                """.trimMargin()
            )
        }
    }

    fun enable() {
        l.info(
            """You will see a file `~/.testcontainers.properties', with the setting 'testcontainers.reuse.enable=true'.
                | If you don't see the file please create by yourself. 
                | Otherwise dependencies won't keep running.
            """.trimIndent()
        )
        when {
            !propertiesFile.exists() -> propertiesFile.writeText(reuseEnabled)
            else -> when {
                propertiesFile.readText()
                    .contains(reuseEnabled) -> l.info(
                    "'.testcontainers.properties' looks good and contains reuse feature!"
                )

                else -> propertiesFile.appendText(reuseEnabled)
            }
        }
    }
}
