# Writing Tests

Here is an example test that validates `http://localhost:$port/hello/index` returns the expected text
=== "Kotest"

    ```kotlin
    class ExampleTest: FunSpec({

        test("should return hi"){
            TestSystem.instance
                .http().get<String>("/hello/index") { actual ->
                    actual shouldContain "Hi from Stove framework"
                }
        }
    })
    ```

=== "JUnit"

    ```kotlin
    class ExampleTest {

        @Test
        fun `should return hi`() {
            TestSystem.instance
                .http().get<String>("/hello/index") { actual ->
                    assertTrue(actual.contains("Hi from Stove framework"))
                }
        }
    })
    ```

That's it! You have up-and-running API, can be tested with Stove.
