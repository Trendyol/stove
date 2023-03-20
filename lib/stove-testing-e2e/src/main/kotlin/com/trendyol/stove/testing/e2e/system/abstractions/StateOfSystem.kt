package com.trendyol.stove.testing.e2e.system.abstractions

import com.fasterxml.jackson.module.kotlin.readValue
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.TestSystemOptions
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.reflect.KClass

data class StateWithProcess<TState : Any>(
    val state: TState,
    val processId: Long,
)

class StateOfSystem<TSystem : Any, TState : Any>(
    val options: TestSystemOptions,
    val system: KClass<TSystem>,
    val state: KClass<TState>,
) {
    private val folderForSystem = Paths.get(
        System.getProperty("java.io.tmpdir"),
        "com.trendyol.stove.testing.e2e"
    )
    private val pathForSystem: Path = folderForSystem.resolve(
        "stove-e2e-${system.simpleName!!.lowercase(Locale.getDefault())}.lock"
    )
    private val j = StoveObjectMapper.Default
    private val l: Logger = LoggerFactory.getLogger(javaClass)

    init {
        if (!folderForSystem.exists()) {
            folderForSystem.createDirectories()
        }
    }

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

    fun isSubsequentRun(): Boolean = pathForSystem.exists() && options.keepDependenciesRunning && isDifferentProcess()

    private fun saveStateForNextRun(state: TState): TState = state.also {
        l.info("State does not exist for ${name()}. System is being saved to: ${pathForSystem.absolutePathString()}")
        pathForSystem.writeBytes(j.writeValueAsBytes(StateWithProcess(state, getPid())))
    }

    private suspend fun recover(otherwise: suspend () -> TState): TState = when {
        pathForSystem.exists() -> {
            l.info("State exists for ${name()}. System is being recovered from: ${pathForSystem.absolutePathString()}")
            val swp = j.readValue<StateWithProcess<TState>>(pathForSystem.readBytes())
            j.convertValue(swp.state, state.java)
        }

        else -> saveStateForNextRun(otherwise())
    }

    private fun isDifferentProcess(): Boolean {
        val swp: StateWithProcess<TState> = j.readValue(pathForSystem.readBytes())
        return swp.processId != getPid()
    }

    private fun name(): String = system.simpleName!!

    private fun getPid(): Long = ProcessHandle.current().pid()
}
