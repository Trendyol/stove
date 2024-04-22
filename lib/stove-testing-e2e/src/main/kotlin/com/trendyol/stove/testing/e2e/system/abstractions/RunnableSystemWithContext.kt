package com.trendyol.stove.testing.e2e.system.abstractions

import com.trendyol.stove.functional.Try
import com.trendyol.stove.functional.recover
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * @author Oguzhan Soykan
 */
interface BeforeRunAware {
  suspend fun beforeRun()
}

/**
 * @author Oguzhan Soykan
 */
interface RunAware {
  suspend fun run()

  suspend fun stop()
}

/**
 * @author Oguzhan Soykan
 */
interface AfterRunAwareWithContext<TContext> {
  suspend fun afterRun(context: TContext)
}

/**
 * @author Oguzhan Soykan
 */
interface AfterRunAware {
  suspend fun afterRun()
}

/**
 * @author Oguzhan Soykan
 */
interface RunnableSystemWithContext<TContext> : AutoCloseable, BeforeRunAware, RunAware, AfterRunAwareWithContext<TContext> {
  private val logger: Logger get() = LoggerFactory.getLogger(javaClass)

  override fun close(): Unit = runBlocking { Try { stop() }.recover { logger.warn("got an error while stopping") } }
}

/**
 * @author Oguzhan Soykan
 */
interface RunnableSystem : AutoCloseable, BeforeRunAware, RunAware, AfterRunAware {
  private val logger: Logger get() = LoggerFactory.getLogger(javaClass)

  override fun close(): Unit = runBlocking { Try { stop() }.recover { logger.warn("got an error while stopping") } }
}
