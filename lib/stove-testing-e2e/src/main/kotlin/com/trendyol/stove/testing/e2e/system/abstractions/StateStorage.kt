@file:Suppress("FunctionName")

package com.trendyol.stove.testing.e2e.system.abstractions

import com.fasterxml.jackson.module.kotlin.readValue
import com.trendyol.stove.testing.e2e.serialization.*
import com.trendyol.stove.testing.e2e.system.*
import org.slf4j.*
import java.nio.file.*
import java.util.*
import kotlin.io.path.*
import kotlin.reflect.KClass

interface StateStorage<TState> {
  suspend fun capture(start: suspend () -> TState): TState

  fun isSubsequentRun(): Boolean
}

interface StateStorageFactory {
  operator fun <T : Any> invoke(options: TestSystemOptions, system: KClass<*>, state: KClass<T>): StateStorage<T>

  companion object {
    fun Default(): StateStorageFactory = DefaultStateStorageFactory()

    private fun DefaultStateStorageFactory(): StateStorageFactory = object : StateStorageFactory {
      override fun <T : Any> invoke(options: TestSystemOptions, system: KClass<*>, state: KClass<T>): StateStorage<T> =
        DefaultStateStorage(options, system, state)
    }
  }

  fun <T : Any> DefaultStateStorage(
    options: TestSystemOptions,
    system: KClass<*>,
    state: KClass<T>
  ): StateStorage<T> = FileSystemStorage(options, system, state)
}

/**
 * Represents the state of the [TestSystem] which is being captured.
 * @param TState the type of the state
 * @param state the state of the [TestSystem]
 * @param processId the process id of the [TestSystem]
 */
data class StateWithProcess<TState : Any>(
  val state: TState,
  val processId: Long
)

internal class FileSystemStorage<TState : Any>(
  val options: TestSystemOptions,
  val system: KClass<*>,
  private val state: KClass<TState>
) : StateStorage<TState> {
  private val folderForSystem =
    Paths.get(
      System.getProperty("java.io.tmpdir"),
      "com.trendyol.stove.testing.e2e"
    )

  private val pathForSystem: Path = folderForSystem.resolve("stove-e2e-${system.simpleName!!.lowercase(Locale.getDefault())}.lock")
  private val j = StoveSerde.jackson.default
  private val l: Logger = LoggerFactory.getLogger(javaClass)

  init {
    if (!folderForSystem.exists()) {
      folderForSystem.createDirectories()
    }
  }

  /**
   * Captures the TestSystem state into the file system. Basically creates a Json file which contains the state of the [PluggedSystem]
   * that is run by the [TestSystem].
   */
  override suspend fun capture(start: suspend () -> TState): TState = when {
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

  /**
   * Returns true if the [TestSystem] is being run for the first time.
   */
  override fun isSubsequentRun(): Boolean = pathForSystem.exists() && options.keepDependenciesRunning && isDifferentProcess()

  /**
   * Recovers the state of the [TestSystem] from the file system.
   */
  private suspend fun recover(otherwise: suspend () -> TState): TState =
    when {
      pathForSystem.exists() -> {
        l.info("State exists for ${name()}. System is being recovered from: ${pathForSystem.absolutePathString()}")
        val swp = j.readValue<StateWithProcess<TState>>(pathForSystem.readBytes())
        j.convertValue(swp.state, state.java)
      }

      else -> saveStateForNextRun(otherwise())
    }

  private fun saveStateForNextRun(state: TState): TState =
    state.also {
      l.info("State does not exist for ${name()}. System is being saved to: ${pathForSystem.absolutePathString()}")
      pathForSystem.writeBytes(j.writeValueAsBytes(StateWithProcess(state, getPid())))
    }

  private fun isDifferentProcess(): Boolean {
    val swp: StateWithProcess<TState> = j.readValue(pathForSystem.readBytes())
    return swp.processId != getPid()
  }

  private fun name(): String = system.simpleName!!

  private fun getPid(): Long = ProcessHandle.current().pid()
}
