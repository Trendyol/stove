package com.trendyol.stove.examples.kotlin.spring

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import org.springframework.boot.*
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

@SpringBootApplication
class ExampleStoveSpringBootApp

fun main(args: Array<String>) {
  run(args)
}

fun run(args: Array<String>, init: SpringApplication.() -> Unit = {}): ConfigurableApplicationContext =
  runApplication<ExampleStoveSpringBootApp>(*args) {
    init()
  }

data class ExampleData(val id: Int, val name: String)

@RestController
@RequestMapping("/api/streaming")
class StreamingController {
  @GetMapping(
    "json",
    produces = [
      MediaType.APPLICATION_NDJSON_VALUE
    ]
  )
  fun json(
    @RequestParam load: Int = 100,
    @RequestParam delay: Long = 1
  ): Flow<ExampleData> = (1..load)
    .asFlow()
    .onEach { delay(delay) }
    .map { ExampleData(it, "name$it") }
}
