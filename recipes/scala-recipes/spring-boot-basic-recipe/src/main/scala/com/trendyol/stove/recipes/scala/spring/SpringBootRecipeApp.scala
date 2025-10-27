package com.trendyol.stove.recipes.scala.spring

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.{
  GetMapping,
  RequestMapping,
  RestController
}

@SpringBootApplication
class SpringBootRecipeApp

object SpringBootRecipeApp {
  def main(args: Array[String]): Unit = run(args, _ => ())

  def run(
      args: Array[String],
      configure: SpringApplication => _
  ): ConfigurableApplicationContext = {
    val app = new SpringApplication(classOf[SpringBootRecipeApp])
    configure(app)
    app.run(args: _*)
  }
}

@RestController
@RequestMapping(Array("/hello"))
class HelloWorldController(
    private val currentThreadRetriever: CurrentThreadRetriever
) {
  @GetMapping
  def hello(): String =
    "Hello, World! from " + currentThreadRetriever.getCurrentThreadName
}

@Component
class CurrentThreadRetriever {
  def getCurrentThreadName: String = Thread.currentThread().getName
}
