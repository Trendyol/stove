package com.trendyol.stove

import kotlin.math.sqrt

/** Gradle apportions its CPU budget among test JVMs; IDE runs use the local CPU budget. */
private fun testJvmConcurrency(): Int = System.getProperty("stove.test.concurrency")
  ?.toInt()?.coerceAtLeast(1)
  ?: (Runtime.getRuntime().availableProcessors() * 0.8).toInt().coerceAtLeast(1)

fun testSpecConcurrency(): Int = sqrt(testJvmConcurrency().toDouble()).toInt().coerceAtLeast(1)

fun testCaseConcurrency(parallelSpecs: Int = testSpecConcurrency()): Int = (testJvmConcurrency() / parallelSpecs).coerceAtLeast(1)
