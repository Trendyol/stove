package com.trendyol.stove.testing.e2e.system.abstractions

import kotlin.reflect.KClass

/**
 * @author Oguzhan Soykan
 */
class SystemNotRegisteredException(
    system: KClass<*>
) : Throwable(
        "${system.simpleName} was not registered. Make sure that you registered your service on TestSystem"
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
