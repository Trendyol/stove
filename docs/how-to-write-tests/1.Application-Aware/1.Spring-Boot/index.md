# Testing a Spring Boot Application

[Here](https://github.com/Trendyol/stove4k/tree/main/examples/spring-example) you can jump immediately to the Spring example application.

You need to add the Stove-Spring dependency to be able to write e2e tests for the Spring application.

`$version = please check the current version`

=== "Gradle"

    ```kotlin
    dependencies {
        testImplementation("com.trendyol:stove-spring-testing-e2e:$version")
        testImplementation("com.trendyol:stove-testing-e2e-http:$version")
    }
    ```

=== "Maven"

    ```xml
     <dependency>
        <groupId>com.trendyol</groupId>
        <artifactId>stove-spring-testing-e2e</artifactId>
        <version>${stove-version}</version>
     </dependency>
     <dependency>
        <groupId>com.trendyol</groupId>
        <artifactId>stove-testing-e2e-http</artifactId>
        <version>${stove-version}</version>
     </dependency>
    ```

[Do not forget to add](../../../index.md#how-to-get) the other dependencies and configure your maven repository settings first.
