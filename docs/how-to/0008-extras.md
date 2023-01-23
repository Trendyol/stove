# Extras

## Writing your own TestSystem

You can write your own system to plug to the testing suite.

As an example:
```kotlin
fun TestSystem.withSchedulerSystem(): TestSystem {
    getOrRegister(SchedulerSystem(this))
    return this
}

fun TestSystem.scheduler(): SchedulerSystem = getOrNone<SchedulerSystem>().getOrElse {
    throw SystemNotRegisteredException(SchedulerSystem::class)
}

class SchedulerSystem(override val testSystem: TestSystem) : AfterRunAware<ApplicationContext>, PluggedSystem {

    private lateinit var scheduler: WaitingRoomScheduler
    private lateinit var backgroundCommandBus: BackgroundCommandBusImpl

    fun advance(): SchedulerSystem {
        scheduler.publishValidProducts()
        return this
    }

    fun advanceBackgroundCommandBus(): SchedulerSystem {
        backgroundCommandBus.dispatchTimeoutNotifications()
        return this
    }

    override suspend fun afterRun(context: ApplicationContext) {
        scheduler = context.getBean()
        backgroundCommandBus = context.getBean()
    }

    override fun close() {}
}
```

Later you can use it in testing;

```kotlin
.then().scheduler().advance()
```

## Accessing an application dependency with a system

As you can see, in the example above, if a system implements
`AfterRunAware<ApplicationContext>` then, `afterRun` method becomes available, in here we have access to applications
dependency container to resolve any bean we need to use.

```kotlin
override suspend fun afterRun(context: ApplicationContext) {
    scheduler = context.getBean()
    backgroundCommandBus = context.getBean()
}
```

