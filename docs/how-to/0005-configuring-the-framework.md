# Configuring the framework

After you've added the dependencies from Stove, it is time to construct he config that runs the physical dependencies.

!!! warning
    We assume that you're using [Kotest](https://kotest.io/) test framework, the following configuration is applicable for Kotest
    but, it should be available in any test framework.

## Create a TestSystemConfig.kt class

Here you can see an example `TestSystemConfig` that is located under `test-e2e` module. It
implements `AbstractProjectConfig`
from Kotest framework that allows us to spin up the dependencies we need. As a side note, they are pluggable
extensions, so if you don't want to add the dependency just comment the invocation out. But, be careful, you won't be
able to use it in testing
since you didn't enable the dependency.

```kotlin
class TestSystemConfig : AbstractProjectConfig() {

    override suspend fun beforeProject() {
        TestSystem(baseUrl = "http://localhost:8001")
            .withDefaultHttp()
            .withCouchbase(bucket = "Stove") { cfg ->
                listOf("couchbase.hosts=${cfg.hostsWithPort}") // You can set spring configuration key
                // at this stage couchbase is available
            }
            .withKafka { cfg ->
                listOf("kafka.bootstrapServers=${cfg.boostrapServers}") // You can set spring configuration key
                // at this stage kafka is available
            }
            .withWireMock(port = 9090)
            .systemUnderTest(
                runner = { parameters ->
                    stove.spring.example.run(parameters) {
                        it.addTestSystemDependencies()
                    }
                },
                withParameters =
                listOf(
                    "server.port=8001",
                    "logging.level.root=warn",
                    "logging.level.org.springframework.web=warn",
                    "spring.profiles.active=default",
                    "kafka.heartbeatInSeconds=2"
                )
            )
            .run()
    }

    override fun extensions(): List<Extension> {
        val listener =
            object : AfterTestListener {
                override suspend fun afterTest(
                    testCase: TestCase,
                    result: TestResult,
                ) {
                    TestSystem.instance.wiremock().validate()
                }
            }

        return listOf(listener)
    }

    override suspend fun afterProject() {
        TestSystem.instance.close()
    }
}
```

Here you can see that there are configuration callbacks for the dependencies. For example:

```kotlin
.withCouchbase(bucket = "Stove") { cfg ->
    listOf(
        "couchbase.hosts=${cfg.hostsWithPort}",
        "couchbase.username=${cfg.username}",
        "couchbase.password=${cfg.password}"
    )
}
```

!!! note
    The Couchbase configuration
    name in the `application.yml` is `couchbase.hosts`, this might differ for your project.

Stove exposes the generated configuration by the execution,
so you can pass the real connection strings and configurations to your Spring application before it starts.
So, your application will start with the physical dependencies that are spun-up by the framework.

## Configuring systemUnderTest

We may know the concept of `service under test` from the Test-Driven-Development.

Here we have the similar concept, since we're testing the entire system, it is called `systemUnderTest`

Every TestSystem must have a _system under test_, and configure it. In here we're configuring a _Spring application
under
test_.

`systemUnderTest` configures how to run the application. `runner` parameter is the entrance point for the Spring
application
that we have configured at [step 1](#step-1-tuning-the-application-entry-point-and-folder-structure)

!!! note
    `server.port=8001` is a Spring config, TestSystem's `baseUrl` needs to match with it, since Http requests are made
    against the `baseUrl` that is defined. `withDefaultHttp` creates a WebClient and uses the `baseUrl` that is passed.
