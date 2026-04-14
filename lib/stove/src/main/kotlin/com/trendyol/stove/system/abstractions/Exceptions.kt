package com.trendyol.stove.system.abstractions

import kotlin.reflect.KClass

/**
 * @author Oguzhan Soykan
 */
class SystemNotRegisteredException(
  system: KClass<*>,
  detail: String? = null
) : Throwable(
  "${system.simpleName} was not registered. " +
    (detail ?: "Make sure that you registered your service on TestSystem")
)

/**
 * @author Oguzhan Soykan
 */
class SystemConfigurationException(
  system: KClass<*>,
  reason: String
) : Throwable(
  "${system.simpleName} configuration got an error: $reason"
)

class SystemNotInitializedException(
  system: KClass<*>
) : Throwable(
  "${system.simpleName} was not initialized. Make sure that you initialized your service on TestSystem"
)
