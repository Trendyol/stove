package com.trendyol.stove.quarkus

import com.trendyol.stove.system.Runner
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.WithDsl
import com.trendyol.stove.system.abstractions.AfterRunAwareWithContext
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.system.abstractions.ReadyStove
import com.trendyol.stove.system.annotations.StoveDsl
import io.quarkus.runtime.Quarkus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference

@StoveDsl
internal fun Stove.systemUnderTest(
  runner: Runner<Unit>,
  withParameters: List<String> = listOf()
): ReadyStove {
  this.applicationUnderTest(QuarkusApplicationUnderTest(this, runner, withParameters))
  return this
}

@StoveDsl
fun WithDsl.quarkus(
  runner: Runner<Unit>,
  withParameters: List<String> = listOf()
): ReadyStove = this.stove.systemUnderTest(runner, withParameters)

@StoveDsl
class QuarkusApplicationUnderTest(
  private val stove: Stove,
  private val runner: Runner<Unit>,
  private val parameters: List<String>
) : ApplicationUnderTest<Unit> {
  private var launcher: QuarkusLauncher? = null

  @Suppress("TooGenericExceptionCaught")
  override suspend fun start(configurations: List<String>): Unit = coroutineScope {
    val quarkusLauncher = createLauncher(configurations)
    launcher = quarkusLauncher
    quarkusLauncher.start()

    try {
      waitForApplication(quarkusLauncher)
      notifySystemsAfterRun()
    } catch (error: Throwable) {
      stopAfterStartupFailure(quarkusLauncher, error)
      launcher = null
      throw error
    }
  }

  override suspend fun stop() {
    launcher?.stop()
    launcher = null
  }

  private suspend fun waitForApplication(quarkusLauncher: QuarkusLauncher) {
    val startTime = System.currentTimeMillis()
    val startupTimeoutMs = resolveTimeoutMillis(
      propertyName = STARTUP_TIMEOUT_PROPERTY,
      defaultValue = DEFAULT_STARTUP_TIMEOUT_MS
    )

    while (true) {
      failIfStartupFailed(quarkusLauncher)
      if (quarkusLauncher.isReady()) return
      failIfStartupTimedOut(quarkusLauncher, startTime, startupTimeoutMs)

      delay(POLL_INTERVAL_MS)
    }
  }

  private suspend fun notifySystemsAfterRun() {
    coroutineScope {
      stove.activeSystems
        .map { it.value }
        .filterIsInstance<AfterRunAwareWithContext<Unit>>()
        .map { async { it.afterRun(Unit) } }
        .awaitAll()
    }
  }

  private fun createLauncher(configurations: List<String>) = QuarkusLauncher(
    runtime = resolveLaunchRuntime(runner),
    configuration = QuarkusConfiguration.from(configurations + parameters)
  )

  @Suppress("TooGenericExceptionCaught")
  private fun stopAfterStartupFailure(quarkusLauncher: QuarkusLauncher, error: Throwable) {
    try {
      quarkusLauncher.stop()
    } catch (stopError: Throwable) {
      error.addSuppressed(stopError)
    }
  }

  private fun failIfStartupFailed(quarkusLauncher: QuarkusLauncher) {
    quarkusLauncher.failureOrNull()?.let { failure ->
      throw IllegalStateException("Quarkus startup failed", failure)
    }
  }

  private fun failIfStartupTimedOut(
    quarkusLauncher: QuarkusLauncher,
    startTime: Long,
    startupTimeoutMs: Long
  ) {
    if (System.currentTimeMillis() - startTime <= startupTimeoutMs) return
    error(readinessTimeoutMessage(quarkusLauncher, startupTimeoutMs))
  }

  private fun readinessTimeoutMessage(quarkusLauncher: QuarkusLauncher, startupTimeoutMs: Long) =
    "Timeout waiting for Quarkus application readiness after ${startupTimeoutMs}ms. " +
      quarkusLauncher.describeReadinessState()
}

private data class LaunchRuntime(
  val modeName: String,
  val launch: (Array<String>) -> Unit,
  val stop: () -> Unit = {},
  val close: () -> Unit = {}
)

private data class QuarkusConfiguration(
  val properties: Map<String, String>,
  val httpPort: Int,
  val readinessHosts: List<String>
) {
  val readinessUrls: List<String> = readinessHosts.map { "http://$it:$httpPort/" }

  companion object {
    fun from(configurations: List<String>): QuarkusConfiguration {
      val properties = parseConfigurations(configurations)
      return QuarkusConfiguration(
        properties = properties,
        httpPort = properties["quarkus.http.port"]?.toIntOrNull() ?: DEFAULT_HTTP_PORT,
        readinessHosts = resolveReadinessHosts(properties)
      )
    }
  }
}

private fun resolveLaunchRuntime(runner: Runner<Unit>): LaunchRuntime =
  resolvePackagedRuntimeArtifacts()
    ?.let(::packagedRuntime)
    ?: directMainRuntime(runner)

private fun directMainRuntime(runner: Runner<Unit>) = LaunchRuntime(
  modeName = "direct-main",
  launch = { args -> runner(args) },
  stop = { Quarkus.asyncExit() }
)

private fun packagedRuntime(packagedRuntimeArtifacts: PackagedRuntimeArtifacts): LaunchRuntime =
  PackagedRuntimeState(packagedRuntimeArtifacts).asRuntime()

private class QuarkusLauncher(
  private val runtime: LaunchRuntime,
  private val configuration: QuarkusConfiguration
) : Closeable {
  private val runnerArguments = emptyArray<String>()
  private val startupFailure = AtomicReference<Throwable?>(null)
  private var previousSystemProperties: Map<String, String?> = emptyMap()
  private var launcherThread: Thread? = null

  @Suppress("TooGenericExceptionCaught")
  fun start() {
    check(launcherThread == null) { "Quarkus launcher already started" }

    prepareStart()

    try {
      val thread = createLauncherThread()
      launcherThread = thread
      thread.start()
    } catch (error: Throwable) {
      cleanupAfterFailedStart()
      throw error
    }
  }

  fun isAlive(): Boolean = launcherThread?.isAlive == true

  fun failureOrNull(): Throwable? = startupFailure.get()

  fun isReady(): Boolean = hasStartupSignal() || configuration.isHttpReady()

  fun describeReadinessState(): String =
    "launcherMode=${runtime.modeName}, " +
      "signalPresent=${hasStartupSignal()}, " +
      "httpReady=${configuration.isHttpReady()}, " +
      "readinessUrls=${configuration.readinessUrls}, " +
      "launcherAlive=${isAlive()}, " +
      "launcherState=${launcherThread?.state ?: "not-started"}"

  @Suppress("TooGenericExceptionCaught")
  fun stop() {
    val thread = launcherThread
    var stopFailure: Throwable? = null

    try {
      stopFailure = stopRunningThread(thread)
    } finally {
      cleanup()
    }

    stopFailure?.let { throw it }
  }

  override fun close() {
    stop()
  }

  private fun hasStartupSignal(): Boolean =
    System.getProperty(DEFAULT_READY_SIGNAL_PROPERTY) == READY_SIGNAL_VALUE

  private fun prepareStart() {
    clearStartupSignal()
    previousSystemProperties = applySystemProperties(configuration.properties)
  }

  private fun createLauncherThread() = Thread(
    { runtime.launchCatching(runnerArguments, startupFailure) },
    "quarkus-main-launcher"
  ).apply {
    isDaemon = false
    setUncaughtExceptionHandler { _, error ->
      startupFailure.compareAndSet(null, unwrap(error))
    }
  }

  private fun cleanupAfterFailedStart() {
    restoreSystemProperties(previousSystemProperties)
    previousSystemProperties = emptyMap()
  }

  @Suppress("TooGenericExceptionCaught")
  private fun stopRunningThread(thread: Thread?): Throwable? {
    if (thread == null || !thread.isAlive) return null

    val stopFailure = try {
      runtime.stop()
      null
    } catch (error: Throwable) {
      error
    }

    thread.join(SHUTDOWN_TIMEOUT_MS)
    if (thread.isAlive) {
      error("Timeout waiting for Quarkus to shut down")
    }
    return stopFailure
  }

  private fun cleanup() {
    clearStartupSignal()
    restoreSystemProperties(previousSystemProperties)
    previousSystemProperties = emptyMap()
    runtime.close()
    launcherThread = null
  }
}

private fun parseConfigurations(configurations: List<String>): Map<String, String> {
  val properties = linkedMapOf<String, String>()
  configurations.forEach { configuration ->
    val separatorIndex = configuration.indexOf('=')
    require(separatorIndex > 0) {
      "Invalid Quarkus configuration '$configuration'. Expected key=value."
    }
    val key = configuration.substring(0, separatorIndex)
    val value = configuration.substring(separatorIndex + 1)
    properties[key] = value
  }
  return properties
}

private fun resolveReadinessHosts(configurationProperties: Map<String, String>): List<String> {
  val configuredHost = configurationProperties["quarkus.http.host"]
  return listOfNotNull(configuredHost, "localhost", "127.0.0.1")
    .filterNot { it == "0.0.0.0" || it == "::" || it == "[::]" }
    .distinct()
}

private fun QuarkusConfiguration.isHttpReady(): Boolean = readinessUrls.any(::isReadyUrl)

private fun isReadyUrl(readinessUrl: String): Boolean {
  val connection = try {
    URI.create(readinessUrl).toURL().openConnection() as HttpURLConnection
  } catch (_: Exception) {
    return false
  }

  return try {
    connection.connectTimeout = CONNECTION_TIMEOUT_MS.toInt()
    connection.readTimeout = CONNECTION_TIMEOUT_MS.toInt()
    connection.requestMethod = "GET"
    connection.instanceFollowRedirects = false
    connection.responseCode
    true
  } catch (_: Exception) {
    false
  } finally {
    connection.disconnect()
  }
}

private fun applySystemProperties(properties: Map<String, String>): Map<String, String?> =
  buildMap {
    properties.forEach { (key, value) ->
      put(key, System.getProperty(key))
      System.setProperty(key, value)
    }
  }

private fun restoreSystemProperties(previousSystemProperties: Map<String, String?>) {
  previousSystemProperties.forEach { (key, previousValue) ->
    if (previousValue == null) {
      System.clearProperty(key)
    } else {
      System.setProperty(key, previousValue)
    }
  }
}

private fun clearStartupSignal() {
  System.clearProperty(DEFAULT_READY_SIGNAL_PROPERTY)
}

private fun resolvePackagedRuntimeArtifacts(): PackagedRuntimeArtifacts? =
  resolveClassPathEntries()
    .mapNotNull { it.findBuildDirectory() }
    .distinct()
    .firstNotNullOfOrNull(::resolvePackagedRuntimeArtifacts)

private fun Path.findBuildDirectory(): Path? =
  generateSequence(this) { current -> current.parent }
    .firstOrNull { current -> current.fileName?.toString() == "build" }

private fun resolveTimeoutMillis(propertyName: String, defaultValue: Long): Long =
  System
    .getProperty(propertyName)
    ?.toLongOrNull()
    ?.takeIf { it > 0 }
    ?: defaultValue

private data class PackagedRuntimeArtifacts(
  val appRoot: Path,
  val applicationDat: Path,
  val bootUrls: Array<URL>
)

private data class PackagedApplication(
  val runnerClassLoader: ClassLoader,
  val mainClassName: String
)

private class PackagedRuntimeState(
  private val packagedRuntimeArtifacts: PackagedRuntimeArtifacts
) {
  private var runnerClassLoader: Any? = null
  private var bootClassLoader: URLClassLoader? = null

  fun asRuntime() = LaunchRuntime(
    modeName = "packaged-runtime",
    launch = ::launch,
    stop = ::stop,
    close = ::close
  )

  fun launch(runnerArguments: Array<String>) {
    withBootClassLoader { packagedBootClassLoader ->
      val packagedApplication = loadPackagedApplication(packagedBootClassLoader)
      launchPackagedApplication(packagedApplication, packagedBootClassLoader, runnerArguments)
    }
  }

  fun stop() {
    (runnerClassLoader as? ClassLoader)?.let(::asyncExit)
  }

  fun close() {
    runnerClassLoader?.let(::closeRunnerClassLoader)
    runnerClassLoader = null
    bootClassLoader?.close()
    bootClassLoader = null
  }

  private fun withBootClassLoader(block: (URLClassLoader) -> Unit) {
    val originalClassLoader = Thread.currentThread().contextClassLoader
    val packagedBootClassLoader = createBootClassLoader(originalClassLoader)

    try {
      block(packagedBootClassLoader)
    } finally {
      restoreContextClassLoader(originalClassLoader, packagedBootClassLoader)
      close()
    }
  }

  private fun createBootClassLoader(originalClassLoader: ClassLoader): URLClassLoader =
    URLClassLoader(packagedRuntimeArtifacts.bootUrls, originalClassLoader).also {
      bootClassLoader = it
    }

  private fun loadPackagedApplication(packagedBootClassLoader: URLClassLoader): PackagedApplication {
    val serializedApplicationClass = loadSerializedApplicationClass(packagedBootClassLoader)
    val serializedApplication = readSerializedApplication(serializedApplicationClass)
    val packagedRunnerClassLoader = serializedApplication.runnerClassLoader(serializedApplicationClass)
    runnerClassLoader = packagedRunnerClassLoader

    return PackagedApplication(
      runnerClassLoader = packagedRunnerClassLoader as ClassLoader,
      mainClassName = serializedApplication.mainClassName(serializedApplicationClass)
    )
  }

  private fun launchPackagedApplication(
    packagedApplication: PackagedApplication,
    packagedBootClassLoader: URLClassLoader,
    runnerArguments: Array<String>
  ) {
    Thread.currentThread().contextClassLoader = packagedApplication.runnerClassLoader
    setForkJoinApplicationClassLoader(packagedBootClassLoader, packagedApplication.runnerClassLoader)
    invokeMain(packagedApplication.runnerClassLoader, packagedApplication.mainClassName, runnerArguments)
  }

  private fun restoreContextClassLoader(
    originalClassLoader: ClassLoader,
    packagedBootClassLoader: URLClassLoader
  ) {
    clearForkJoinApplicationClassLoader(packagedBootClassLoader)
    Thread.currentThread().contextClassLoader = originalClassLoader
  }

  private fun loadSerializedApplicationClass(packagedBootClassLoader: URLClassLoader): Class<*> =
    Class.forName(SERIALIZED_APPLICATION_CLASS_NAME, true, packagedBootClassLoader)

  private fun readSerializedApplication(serializedApplicationClass: Class<*>): Any =
    BufferedInputStream(packagedRuntimeArtifacts.applicationDat.toFile().inputStream()).use { input ->
      serializedApplicationClass
        .getMethod("read", java.io.InputStream::class.java, Path::class.java)
        .invoke(null, input, packagedRuntimeArtifacts.appRoot)
    }
}

@Suppress("TooGenericExceptionCaught")
private fun LaunchRuntime.launchCatching(
  runnerArguments: Array<String>,
  startupFailure: AtomicReference<Throwable?>
) {
  try {
    launch(runnerArguments)
  } catch (error: Throwable) {
    startupFailure.compareAndSet(null, unwrap(error))
  }
}

private fun resolveClassPathEntries(): List<Path> {
  val pathSeparator = File.pathSeparator
  return System
    .getProperty("java.class.path")
    .split(pathSeparator)
    .map { Paths.get(it).toAbsolutePath().normalize() }
}

private fun resolvePackagedRuntimeArtifacts(buildDirectory: Path): PackagedRuntimeArtifacts? {
  val appRoot = buildDirectory.resolve("quarkus-app")
  val applicationDat = appRoot.resolve("quarkus/quarkus-application.dat")
  val bootUrls = resolveBootUrls(appRoot.resolve("lib/boot"))
  if (!applicationDat.toFile().exists() || bootUrls.isEmpty()) return null

  return PackagedRuntimeArtifacts(
    appRoot = appRoot,
    applicationDat = applicationDat,
    bootUrls = bootUrls.toTypedArray()
  )
}

private fun resolveBootUrls(bootDirectory: Path): List<URL> =
  bootDirectory
    .toFile()
    .listFiles { file -> file.extension == "jar" }
    ?.sortedBy { it.name }
    ?.map { it.toURI().toURL() }
    .orEmpty()

private fun Any.runnerClassLoader(serializedApplicationClass: Class<*>): Any =
  serializedApplicationClass.getMethod("getRunnerClassLoader").invoke(this)

private fun Any.mainClassName(serializedApplicationClass: Class<*>): String =
  serializedApplicationClass.getMethod("getMainClass").invoke(this) as String

private fun invokeMain(appClassLoader: ClassLoader, mainClassName: String, runnerArguments: Array<String>) {
  appClassLoader
    .loadClass(mainClassName)
    .getMethod("main", Array<String>::class.java)
    .invoke(null, runnerArguments)
}

private fun asyncExit(appClassLoader: ClassLoader) {
  appClassLoader.loadClass(QUARKUS_CLASS_NAME).getMethod("asyncExit").invoke(null)
}

private fun setForkJoinApplicationClassLoader(bootClassLoader: URLClassLoader, appClassLoader: ClassLoader) {
  val forkJoinWorkerThreadClass = Class.forName(FORK_JOIN_WORKER_THREAD_CLASS_NAME, true, bootClassLoader)
  forkJoinWorkerThreadClass.getMethod("setQuarkusAppClassloader", ClassLoader::class.java).invoke(null, appClassLoader)
}

private fun clearForkJoinApplicationClassLoader(bootClassLoader: URLClassLoader) {
  val forkJoinWorkerThreadClass = Class.forName(FORK_JOIN_WORKER_THREAD_CLASS_NAME, true, bootClassLoader)
  forkJoinWorkerThreadClass.getMethod("setQuarkusAppClassloader", ClassLoader::class.java).invoke(null, null)
}

private fun closeRunnerClassLoader(runnerClassLoader: Any) {
  runnerClassLoader.javaClass.getMethod("close").invoke(runnerClassLoader)
}

private fun unwrap(error: Throwable): Throwable = when (error) {
  is InvocationTargetException -> error.targetException ?: error
  else -> error.cause?.takeIf { error is RuntimeException && error.message == null } ?: error
}

private const val DEFAULT_HTTP_PORT = 8080
private const val DEFAULT_STARTUP_TIMEOUT_MS = 120_000L
private const val SHUTDOWN_TIMEOUT_MS = 10_000L
private const val POLL_INTERVAL_MS = 250L
private const val CONNECTION_TIMEOUT_MS = 500L
private const val DEFAULT_READY_SIGNAL_PROPERTY = "stove.quarkus.ready"
private const val READY_SIGNAL_VALUE = "true"
private const val STARTUP_TIMEOUT_PROPERTY = "stove.quarkus.startup.timeout.ms"
private const val FORK_JOIN_WORKER_THREAD_CLASS_NAME = "io.quarkus.bootstrap.forkjoin.QuarkusForkJoinWorkerThread"
private const val SERIALIZED_APPLICATION_CLASS_NAME = "io.quarkus.bootstrap.runner.SerializedApplication"
private const val QUARKUS_CLASS_NAME = "io.quarkus.runtime.Quarkus"
