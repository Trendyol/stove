# Replacing Dependencies For Better Testability

When it comes to handling the time, no one wants to wait for 30 minutes for a scheduler job, or for a delayed task to be
able to test it.
In these situations what we need to do is `advancing` the time, or replacing the effect of the time for our needs. This
may require you
to change your code, too. Because, we might need to provide a time-free implementation to an interface, or we might need
to extract it
to an interface if not properly implemented.

For example, in international-service project we have a delayed command executor that accepts a task and a time for it
to delay it until
it is right time to execute. But, in tests we need to replace this behaviour with the time-effect free implementation.

```kotlin
class BackgroundCommandBusImpl // is the class for delayed operations
```

We would like to by-pass the time-bounded logic inside BackgroundCommandBusImpl, and for e2eTest scope we write:

```kotlin
class NoDelayBackgroundCommandBusImpl(
    backgroundMessageEnvelopeDispatcher: BackgroundMessageEnvelopeDispatcher,
    backgroundMessageEnvelopeStorage: BackgroundMessageEnvelopeStorage,
    lockProvider: CouchbaseLockProvider,
) : BackgroundCommandBusImpl(
    backgroundMessageEnvelopeDispatcher,
    backgroundMessageEnvelopeStorage,
    lockProvider
) {

    override suspend fun <TNotification : BackgroundNotification> publish(
        notification: TNotification,
        options: BackgroundOptions,
    ) {
        super.publish(notification, options.withDelay(0))
    }

    override suspend fun <TCommand : BackgroundCommand> send(
        command: TCommand,
        options: BackgroundOptions,
    ) {
        super.send(command, options.withDelay(0))
    }
}
```

Now, it is time to tell to e2eTest system to use NoDelay implementation.

That brings us to initializers.
