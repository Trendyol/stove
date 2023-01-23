# Tuning the application entry point and folder structure

In a Spring application you usually have an entry point. Let's dive into to the details of the startup.

```kotlin
@SpringBootApplication
class QCInternationalServiceApplication // Empty application class for Spring dependency scan

/**
 * Entry point for the jvm application
 */
fun main(args: Array<String>) {
    run(args)
}

/**
 * This is the point where spring application gets run.
 */
fun run(
    args: Array<String>,
    init: SpringApplication.() -> Unit = {},
): ConfigurableApplicationContext =
    runApplication<QCInternationalServiceApplication>(
        *args,
        init = init
    )
```

`run(args, init)` method is the important point for the testing configuration. `init` allows us to override any
dependency
from the testing side that is being `time` related or `configuration` related. Spring itself opens this configuration
higher order function to the outside.

Secondly, it is up you to run e2e tests with your CI depending on the branch. But, we would like to give some advice for
the
folder/module structure.

![Folder structure of International Service](../assets/folder-structure.png)

As you can see, unit tests, e2e tests, and integration tests are seperated from each other. So, you can run them
separately.
