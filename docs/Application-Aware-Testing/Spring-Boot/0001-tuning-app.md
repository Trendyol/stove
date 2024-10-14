# Tuning the application's entry point

!!! note
    This change is an optional change. If you don't have any unpredictable components to alter, like, time, scheduler, etc...
    Or if you don't want to have full observability over Kafka you can leave the main function as is. 
    But, still we recommend this change, as you might have components to change in the future.

Let's say the application has a standard `main` function, here how we will change it:

=== "Before"

    ```kotlin
    @SpringBootApplication
    class ExampleApplication

    fun main(args: Array<String>) { runApplication<ExampleApplication>(*args) }
    ```

=== "After"

    ```kotlin
    @SpringBootApplication
    class ExampleApplication

    fun main(args: Array<String>) { run(args) }

    fun run(
         args: Array<String>,
         init: SpringApplication.() -> Unit = {},
      ): ConfigurableApplicationContext {
            return runApplication<ExampleApplication>(*args, init = init)
        }
    ```

As you can see from `before-after` sections, we have divided the application main function into two parts.

`run(args, init)` method is the important point for the testing configuration. `init` allows us to override any
dependency
from the testing side that is being `time` related or `configuration` related. Spring itself opens this configuration
higher order function to the outside.
