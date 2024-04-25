package com.stove.ktor.example.e2e

import org.koin.core.module.Module
import org.koin.dsl.*
import stove.ktor.example.application.LockProvider
import java.time.Duration

fun addTestSystemDependencies(): Module =
  module {
    single { NoOpLockProvider() }.bind<LockProvider>()
  }

class NoOpLockProvider : LockProvider {
  override suspend fun acquireLock(
    name: String,
    duration: Duration
  ): Boolean {
    println("from NoOpLockProvider")
    return true
  }

  override suspend fun releaseLock(name: String) = Unit
}
