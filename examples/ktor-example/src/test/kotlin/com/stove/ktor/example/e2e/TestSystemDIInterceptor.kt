package com.stove.ktor.example.e2e

import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import stove.ktor.example.LockProvider
import java.time.Duration

fun addTestSystemDependencies(): Module =
    module {
        singleOf(::NoOpLockProvider) { bind<LockProvider>() }
    }

class NoOpLockProvider : LockProvider {
    override suspend fun acquireLock(
        name: String,
        duration: Duration
    ): Boolean {
        println("from NoOpLockProvider")
        return true
    }

    override suspend fun releaseLock(name: String) {}
}
