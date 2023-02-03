# Testing a Spring Boot Application

Spring-Boot applications tend to have an entry point. This is usually a `main` function that starts application with
the given configuration.

[Here](https://github.com/Trendyol/stove4k/tree/main/examples/spring-example) you can jump immediately to the example application.

You need to add the Stove-Spring dependency to be able to write e2e tests for the Spring application.

`$version = please check the current version`

=== "Gradle"
    ``` kotlin
    dependencies {
        testImplementation("com.trendyol:stove-spring-testing-e2e:$version")
    }
    ```

=== "Maven"

    ```xml
     <dependency>
        <groupId>com.trendyol</groupId>
        <artifactId>stove-spring-testing-e2e</artifactId>
        <version>${stove-version}</version>
     </dependency>

    ```

[Do not forget to add](../../../index.md#how-to-get) the other dependencies and configure your maven repository settings first.
