# Attaching

There are entry point for every application, usually a `main` method that is invoked, and starts the application
lifecycle.
If you are publishing your application/api/microservice as a docker image, `docker run ...` basically runs your
application
highly likely with a `jvm/java` command.

In this approach, we're attaching Stove to the very entrance of the application, in other words to its `main` function.

Stove calls your application's `main` function like you would call `java yourApplicationName.jar` to run the application
from the test context.
So the runner is JUnit or Kotest.

For Stove to attach properly to your application, application's main function needs to allow that. This does not change
behaviour at all, it just opens a door for e2e testing framework to _enter_. We will discuss this later.

High level diagram:

![img](../../assets/stove-highlevel.svg)
