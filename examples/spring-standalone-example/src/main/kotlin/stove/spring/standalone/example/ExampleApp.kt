package stove.spring.standalone.example

import org.springframework.boot.*
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.ConfigurableApplicationContext

@SpringBootApplication
class ExampleApp

fun main(args: Array<String>) {
  run(args)
}

/**
 * This is the point where spring application gets run.
 * run(args, init) method is the important point for the testing configuration.
 * init allows us to override any dependency from the testing side that is being time related or configuration related.
 * Spring itself opens this configuration higher order function to the outside.
 */
fun run(
  args: Array<String>,
  init: SpringApplication.() -> Unit = {}
): ConfigurableApplicationContext = runApplication<ExampleApp>(*args, init = init)
