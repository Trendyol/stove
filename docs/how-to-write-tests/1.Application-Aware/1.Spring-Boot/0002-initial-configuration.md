# Initial Configuration

After you've added the dependencies, and configured the application's `main` function,
it is time to run your application for the first time from the test-context with Stove.

!!! note
    At this stage you can create a new e2e testing module, or use your existing test module in your project.

## Setting up Stove for the Runner

=== "Kotest"

    It implements `AbstractProjectConfig` from Kotest framework that allows us to spin up Stove per run. This is project
    wide operation and executes **only one time**, as the name implies `beforeProject`.
    
    ```kotlin hl_lines="8-13"
    class TestSystemConfig : AbstractProjectConfig() {
    
        override suspend fun beforeProject(): Unit = 
            TestSystem(baseUrl = "http://localhost:8001")
                .with {
                    httpClient()
                    springBoot(
                        runner = { parameters ->
                            /* 
                            *  As you remember, we have divided application's main 
                            *  function into two parts, main and run. 
                            *  We use `run` invocation here.
                            * */
                            stove.spring.example.run(parameters)
                        },
                        withParameters = listOf(
                            "server.port=8001",
                            "logging.level.root=warn",
                            "logging.level.org.springframework.web=warn",
                            "spring.profiles.active=default"
                        )
                    )
                }.run()
    
        override suspend fun afterProject(): Unit = TestSystem.stop()
    }
    ```

=== "JUnit"

    ```kotlin
    class TestSystemConfig {
    
        @BeforeAll
        fun beforeProject() = runBlocking {
             TestSystem(baseUrl = "http://localhost:8001")
                .with {
                    httpClient()
                    springBoot(
                        runner = { parameters ->
                            /* 
                            *  As you remember, we have divided application's main 
                            *  function into two parts, main and run. 
                            *  We use `run` invocation here.
                            * */
                            stove.spring.example.run(parameters)
                        },
                        withParameters = listOf(
                            "server.port=8001",
                            "logging.level.root=warn",
                            "logging.level.org.springframework.web=warn",
                            "spring.profiles.active=default"
                        )
                    )
                }.run()
        }
    
        @AfterAll
        fun afterProject() = runBlocking {
            TestSystem.stop()
        }
    }
    ```

## Configuring systemUnderTest

We may know the concept of `service under test` from the Test-Driven-Development.

Here we have the similar concept, since we're testing the entire system, it is called `systemUnderTest`

Every TestSystem must have a _system under test_, and configure it.
In here we're configuring a _Spring application under test_.

`systemUnderTest` configures how to run the application. `runner` parameter is the entrance point for the Spring
application
that we have configured at [step 1](0001-tuning-app.md#tuning-the-applications-entry-point)

!!! note
`server.port=8001` is a Spring config, TestSystem's `baseUrl` needs to match with it, since Http requests are made
against the `baseUrl` that is defined. `withDefaultHttp` creates a WebClient and uses the `baseUrl` that is passed.
