package com.trendyol.stove.cucumberfixtures.startup

object Startup {
  @JvmStatic
  @io.cucumber.java.BeforeAll
  fun start(): Unit = error("application startup failed")
}
