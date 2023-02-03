# Tuning the application's entry point

In a Spring application you usually have an entry point. If we assume that your application has a standard `main`
function, here how we will change it:

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
