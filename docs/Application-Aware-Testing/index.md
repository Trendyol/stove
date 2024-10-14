# Application Aware Testing

There are entry point for every application, usually a `main` method that is invoked, and starts the application
lifecycle.
If you are publishing your `application/api/microservice` as a docker image, `docker run ...` basically runs your
application
highly likely with a `jvm/java` command.

In this approach, we're using the `main` function of your application in the test context to run the application as
full-blown
as if it is invoked from outside.

Stove calls your application's `main` function like you would call `java yourApplicationName.jar` to run the application
from the test context.
So the runner is JUnit or Kotest.

For Stove to attach properly to your application, application's main function needs to allow that. This does not change
behaviour at all, it just opens a door for e2e testing framework to _enter_. We will discuss this later.

## When to use this approach?

This approach has lots of benefits besides of providing a debug ability while e2e testing. With this approach, you can:

- Debug the application code
- Replace the implementations of the interfaces. Useful for time-bounded implementations such as schedulers, background
  workers, and time itself.
- Have more control over Kafka messages, you would have control over publishing and consuming,
  with [dockerized](../2.Dockerized) approach
  you would only have consuming.
- Use and expose application's dependency container. This is useful if you want to write your own system.
  Say, if you have a dependency that Stove didn't implement yet, you can go ahead and implement it yourself by using the
  [abstractions](../../abstractions). We will discuss it later.
