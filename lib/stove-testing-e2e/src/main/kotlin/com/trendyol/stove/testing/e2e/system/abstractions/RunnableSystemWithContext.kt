package com.trendyol.stove.testing.e2e.system.abstractions

import kotlinx.coroutines.runBlocking

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

    override fun close(): Unit = runBlocking { stop() }
}

/**
 * @author Oguzhan Soykan
 */
interface RunnableSystem : AutoCloseable, BeforeRunAware, RunAware, AfterRunAware {

    override fun close(): Unit = runBlocking { stop() }
}
