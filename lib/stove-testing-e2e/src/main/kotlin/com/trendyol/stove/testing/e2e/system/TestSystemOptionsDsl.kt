package com.trendyol.stove.testing.e2e.system

import java.nio.file.Path
import java.nio.file.Paths
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.io.path.appendText
import kotlin.io.path.exists
import kotlin.io.path.writeText

class TestSystemOptionsDsl {
    private val l: Logger = LoggerFactory.getLogger(javaClass)
    private val propertiesFile: Path = Paths.get(
        System.getProperty("user.home"),
        ".testcontainers.properties"
    )

    internal var options = TestSystemOptions()
    fun keepDependenciesRunning(): TestSystemOptionsDsl {
        l.info(
            """You have chosen to keep dependencies running. For that Stove needs '.testcontainers.properties' file under your user(~/).
                | To add that call `enableReuseForTestContainers` method
            """.trimMargin()
        )
        options = options.copy(keepDependenciesRunning = true)
        return this
    }

    fun isRunningLocally(): Boolean = !isRunningOnCI()

    fun enableReuseForTestContainers() {
        l.info(
            """You will see a file `~/.testcontainers.properties', with the setting 'testcontainers.reuse.enable=true', if you don't see
                | the file please create by yourself. Otherwise dependencies won't keep running.
            """.trimIndent()
        )
        if (!propertiesFile.exists()) {
            propertiesFile.appendText(reuseEnabled)
        } else {
            propertiesFile.writeText(reuseEnabled)
        }
    }

    private fun isRunningOnCI(): Boolean = System.getenv("CI") == "true" ||
        System.getenv("GITLAB_CI") == "true" ||
        System.getenv("GITHUB_ACTIONS") == "true"

    companion object {
        const val reuseEnabled = "testcontainers.reuse.enable=true"
    }
}
