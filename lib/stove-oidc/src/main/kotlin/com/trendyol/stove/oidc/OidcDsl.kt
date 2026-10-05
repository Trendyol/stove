package com.trendyol.stove.oidc

import arrow.core.getOrElse
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.*

fun WithDsl.oidc(configure: () -> OidcSystemOptions = { OidcSystemOptions() }): Stove =
  stove.getOrRegister(OidcSystem(stove, configure())).let { stove }

fun WithDsl.oidc(key: SystemKey, configure: () -> OidcSystemOptions = { OidcSystemOptions() }): Stove =
  stove.getOrRegister(key, OidcSystem(stove, configure(), keyDisplayName(key))).let { stove }

suspend fun <T> ValidationDsl.oidc(block: suspend OidcSystem.() -> T): T =
  block(stove.getOrNone<OidcSystem>().getOrElse { throw SystemNotRegisteredException(OidcSystem::class) })

suspend fun <T> ValidationDsl.oidc(key: SystemKey, block: suspend OidcSystem.() -> T): T =
  block(
    stove.getOrNone<OidcSystem>(key).getOrElse {
      throw SystemNotRegisteredException(
        OidcSystem::class,
        "No OidcSystem registered with key '${keyDisplayName(key)}'. Register it with oidc(key) in Stove.with."
      )
    }
  )
