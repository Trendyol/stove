package com.trendyol.stove.testing.e2e.system.abstractions

import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.TestSystemOptions
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.io.path.*
import kotlin.reflect.KClass

class StateOfSystem<TSystem : Any, TState : Any>(
    val options: TestSystemOptions,
    val system: KClass<TSystem>,
    private val state: KClass<TState>,
) {
    private val pathForSystem: Path = Paths.get(
        System.getProperty("java.io.tmpdir"),
        "stove-e2e-${system.simpleName!!.lowercase(Locale.getDefault())}.lock"
    )
    private val j = StoveObjectMapper.Default
    private val l: Logger = LoggerFactory.getLogger(javaClass)

    suspend fun capture(start: suspend () -> TState): TState = when {
        !options.keepDependenciesRunning -> {
            l.info("State for ${name()} is being deleted at the path: ${pathForSystem.absolutePathString()}")
            pathForSystem.deleteIfExists()
            start()
        }

        pathForSystem.exists() && options.keepDependenciesRunning -> recover(otherwise = start)
        !pathForSystem.exists() && options.keepDependenciesRunning -> saveStateForNextRun(start())
        else -> {
            pathForSystem.deleteIfExists()
            start()
        }
    }

    fun isSubsequentRun(): Boolean = pathForSystem.exists() && options.keepDependenciesRunning

    private fun saveStateForNextRun(state: TState): TState = state.also {
        l.info("State does not exist for ${name()}. System is being saved to: ${pathForSystem.absolutePathString()}")
        pathForSystem.writeBytes(j.writeValueAsBytes(state))
    }

    private suspend fun recover(otherwise: suspend () -> TState): TState = when {
        pathForSystem.exists() -> {
            l.info("State exists for ${name()}. System is being recovered from: ${pathForSystem.absolutePathString()}")
            j.readValue(pathForSystem.readBytes(), state.java)
        }
        else -> saveStateForNextRun(otherwise())
    }

    private fun name(): String = system.simpleName!!
}
