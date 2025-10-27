package com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.kafka

data class Topic(
  val name: String,
  val retry: String,
  val deadLetter: String,
  val maxRetry: Int = 1,
  val concurrency: Int = 1
)
