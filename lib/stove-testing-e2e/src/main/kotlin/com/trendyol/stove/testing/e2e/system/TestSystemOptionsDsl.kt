package com.trendyol.stove.testing.e2e.system

import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@StoveDsl
class TestSystemOptionsDsl {
    private val l: Logger = LoggerFactory.getLogger(javaClass)
    private val propertiesFile = PropertiesFile()

    internal var options = TestSystemOptions()

    @StoveDsl
    fun keepDependenciesRunning(): TestSystemOptionsDsl {
        l.info(
            """You have chosen to keep dependencies running. 
                | For that Stove needs '.testcontainers.properties' file under your user(~/).
                | To add that call '${TestSystemOptionsDsl::enableReuseForTestContainers.name}' method
            """.trimMargin()
        )

        propertiesFile.detectAndLogStatus()
        options = options.copy(keepDependenciesRunning = true)
        return this
    }

    @StoveDsl
    fun isRunningLocally(): Boolean = !isRunningOnCI()

    @StoveDsl
    fun enableReuseForTestContainers(): Unit = propertiesFile.enable()

    private fun isRunningOnCI(): Boolean =
        System.getenv("CI") == "true" ||
            System.getenv("GITLAB_CI") == "true" ||
            System.getenv("GITHUB_ACTIONS") == "true"
}
