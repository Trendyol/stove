package com.trendyol.stove.testing.e2e.system.abstractions

import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.TestSystemOptions
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.reflect.KClass

class StateOfSystem<TSystem : Any, TState : Any>(
    val options: TestSystemOptions,
    val system: KClass<TSystem>,
    private val state: KClass<TState>,
) {
    private val pathForSystem: Path = Paths.get(System.getProperty("java.io.tmpdir"), "stove-e2e-${system.java}.lock")
    private val j = StoveObjectMapper.Default

    suspend fun capture(start: suspend () -> TState): TState = when {
        !options.keepDependenciesRunning -> {
            pathForSystem.deleteIfExists()
            start()
        }

        pathForSystem.exists() && options.keepDependenciesRunning -> recoverInternal(otherwise = start)
        !pathForSystem.exists() && options.keepDependenciesRunning -> saveStateForNextRun(start())
        else -> {
            pathForSystem.deleteIfExists()
            start()
        }
    }

    fun isSubsequentRun(): Boolean = pathForSystem.exists() && options.keepDependenciesRunning

    private fun saveStateForNextRun(state: TState): TState = state.also {
        pathForSystem.writeBytes(j.writeValueAsBytes(state))
    }

    private suspend fun recoverInternal(otherwise: suspend () -> TState): TState =
        when (pathForSystem.exists()) {
            true -> j.readValue(pathForSystem.readBytes(), state.java)
            false -> saveStateForNextRun(otherwise())
        }
}
