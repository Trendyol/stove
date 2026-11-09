package com.trendyol.stove.quarkus

import java.io.BufferedInputStream
import java.net.URLClassLoader
import java.nio.file.Path

private data class PackagedApplication(
  val runnerClassLoader: ClassLoader,
  val mainClassName: String
)

internal class PackagedQuarkusRuntime(
  private val packagedRuntimeArtifacts: PackagedRuntimeArtifacts
) : QuarkusLaunchRuntime {
  @Volatile private var lifecycle: QuarkusRuntimeLifecycle? = null
  private var runnerClassLoader: ClassLoader? = null
  private var bootClassLoader: URLClassLoader? = null

  override val modeName: String = "packaged-runtime"

  override fun isStarted(): Boolean = lifecycle?.isStarted() == true

  override fun launch(args: Array<String>) {
    withBootClassLoader { packagedBootClassLoader ->
      val packagedApplication = loadPackagedApplication(packagedBootClassLoader)
      launchPackagedApplication(packagedApplication, packagedBootClassLoader, args)
    }
  }

  override fun stop() {
    runnerClassLoader?.let(::asyncExit)
  }

  override fun close() {
    runnerClassLoader?.let(::closeRunnerClassLoader)
    runnerClassLoader = null
    lifecycle = null
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
      runnerClassLoader = packagedRunnerClassLoader,
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
    val applicationLifecycle = QuarkusRuntimeLifecycle(packagedApplication.runnerClassLoader)
    lifecycle = applicationLifecycle
    applicationLifecycle.run {
      invokeMain(packagedApplication.runnerClassLoader, packagedApplication.mainClassName, runnerArguments)
    }
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

private fun Any.runnerClassLoader(serializedApplicationClass: Class<*>): ClassLoader =
  serializedApplicationClass.getMethod("getRunnerClassLoader").invoke(this) as ClassLoader

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

private fun closeRunnerClassLoader(runnerClassLoader: ClassLoader) {
  runnerClassLoader.javaClass.getMethod("close").invoke(runnerClassLoader)
}

private const val FORK_JOIN_WORKER_THREAD_CLASS_NAME = "io.quarkus.bootstrap.forkjoin.QuarkusForkJoinWorkerThread"
private const val SERIALIZED_APPLICATION_CLASS_NAME = "io.quarkus.bootstrap.runner.SerializedApplication"
private const val QUARKUS_CLASS_NAME = "io.quarkus.runtime.Quarkus"
