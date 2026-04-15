package com.trendyol.stove.process

import com.trendyol.stove.system.ReadinessStrategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessApplicationUnderTestTest :
  FunSpec({
    context("Server target") {
      test("starts process and injects port env var") {
        val port = java.net.ServerSocket(0).use { it.localPort }

        val aut = ProcessApplicationUnderTest(
          ProcessApplicationOptions(
            command = listOf("sh", "-c", "echo PORT=\$APP_PORT && sleep 5"),
            target = ProcessTarget.Server(
              port = port,
              portEnvVar = "APP_PORT",
              readiness = ReadinessStrategy.FixedDelay(100.milliseconds)
            ),
            envProvider = EnvProvider.empty()
          )
        )

        runBlocking {
          aut.start(emptyList())
          aut.stop()
        }
      }

      test("passes Stove configurations through envProvider") {
        val aut = ProcessApplicationUnderTest(
          ProcessApplicationOptions(
            command = listOf(
              "sh",
              "-c",
              "[ \"\$DB_HOST\" = 'localhost' ] && [ \"\$DB_PORT\" = '5432' ] && exit 0 || exit 1"
            ),
            target = ProcessTarget.Worker(
              readiness = ReadinessStrategy.FixedDelay(100.milliseconds)
            ),
            envProvider = envMapper {
              "database.host" to "DB_HOST"
              "database.port" to "DB_PORT"
            }
          )
        )

        runBlocking {
          aut.start(listOf("database.host=localhost", "database.port=5432"))
          aut.stop()
        }
      }

      test("static and computed env vars are injected") {
        val aut = ProcessApplicationUnderTest(
          ProcessApplicationOptions(
            command = listOf(
              "sh",
              "-c",
              "[ \"\$APP_ENV\" = 'test' ] && [ \"\$COMPUTED\" = 'hello' ] || exit 1; sleep 1"
            ),
            target = ProcessTarget.Worker(
              readiness = ReadinessStrategy.FixedDelay(100.milliseconds)
            ),
            envProvider = envMapper {
              env("APP_ENV", "test")
              env("COMPUTED") { "hello" }
            }
          )
        )

        runBlocking {
          aut.start(emptyList())
          aut.stop()
        }
      }
    }

    context("beforeStarted callback") {
      test("is called with configurations and options before process starts") {
        lateinit var capturedConfigs: Map<String, String>
        lateinit var capturedOptions: ProcessApplicationOptions

        val aut = ProcessApplicationUnderTest(
          ProcessApplicationOptions(
            command = listOf("sh", "-c", "sleep 5"),
            target = ProcessTarget.Worker(
              readiness = ReadinessStrategy.FixedDelay(100.milliseconds)
            ),
            envProvider = envMapper {
              "database.host" to "DB_HOST"
            },
            beforeStarted = { configs, opts ->
              capturedConfigs = configs
              capturedOptions = opts
            }
          )
        )

        runBlocking {
          aut.start(listOf("database.host=localhost", "database.port=5432"))
          capturedConfigs shouldBe mapOf("database.host" to "localhost", "database.port" to "5432")
          capturedOptions.command shouldBe listOf("sh", "-c", "sleep 5")
          capturedOptions.workingDirectory shouldBe null
          aut.stop()
        }
      }

      test("options include working directory when set") {
        val tempDir = kotlin.io.path.createTempDirectory("stove-test").toFile()
        lateinit var capturedOptions: ProcessApplicationOptions

        try {
          val aut = ProcessApplicationUnderTest(
            ProcessApplicationOptions(
              command = listOf("sh", "-c", "sleep 5"),
              target = ProcessTarget.Worker(
                readiness = ReadinessStrategy.FixedDelay(100.milliseconds)
              ),
              beforeStarted = { _, opts -> capturedOptions = opts },
              workingDirectory = tempDir
            )
          )

          runBlocking {
            aut.start(emptyList())
            capturedOptions.workingDirectory shouldBe tempDir
            aut.stop()
          }
        } finally {
          tempDir.deleteRecursively()
        }
      }
    }

    context("Worker target") {
      test("starts without port injection") {
        val aut = ProcessApplicationUnderTest(
          ProcessApplicationOptions(
            command = listOf("sh", "-c", "sleep 5"),
            target = ProcessTarget.Worker(
              readiness = ReadinessStrategy.FixedDelay(100.milliseconds)
            )
          )
        )

        runBlocking {
          aut.start(emptyList())
          aut.stop()
        }
      }
    }

    context("stop lifecycle") {
      test("stop terminates a running process") {
        val aut = ProcessApplicationUnderTest(
          ProcessApplicationOptions(
            command = listOf("sleep", "30"),
            target = ProcessTarget.Worker(
              readiness = ReadinessStrategy.FixedDelay(100.milliseconds)
            ),
            gracefulShutdownTimeout = 5.seconds
          )
        )

        runBlocking {
          aut.start(emptyList())
          aut.stop()
        }
        // No exception — process was stopped successfully
      }

      test("stop is no-op when process was never started") {
        val aut = ProcessApplicationUnderTest(
          ProcessApplicationOptions(
            command = listOf("sh", "-c", "sleep 1"),
            target = ProcessTarget.Worker()
          )
        )

        runBlocking { aut.stop() }
        // No exception — success
      }
    }

    context("readiness") {
      test("fails when process exits before readiness check") {
        shouldThrow<Exception> {
          val aut = ProcessApplicationUnderTest(
            ProcessApplicationOptions(
              command = listOf("sh", "-c", "exit 1"),
              target = ProcessTarget.Server(
                port = 19999,
                readiness = ReadinessStrategy.TcpPort(
                  port = 19999,
                  retries = 2,
                  retryDelay = 50.milliseconds
                )
              )
            )
          )
          runBlocking { aut.start(emptyList()) }
        }
      }

      test("readiness probe succeeds") {
        var ready = false
        val aut = ProcessApplicationUnderTest(
          ProcessApplicationOptions(
            command = listOf("sh", "-c", "sleep 30"),
            target = ProcessTarget.Worker(
              readiness = ReadinessStrategy.Probe(retries = 5, retryDelay = 50.milliseconds) {
                ready = true
                true
              }
            )
          )
        )

        runBlocking {
          aut.start(emptyList())
          ready shouldBe true
          aut.stop()
        }
      }
    }

    context("ProcessTarget defaults") {
      test("Server defaults to HTTP health check on /health") {
        val target = ProcessTarget.Server(port = 8080)
        val readiness = target.readiness
        (readiness is ReadinessStrategy.HttpGet) shouldBe true
        (readiness as ReadinessStrategy.HttpGet).url shouldContain "8080"
        readiness.url shouldContain "/health"
      }

      test("Server accepts custom portEnvVar") {
        val target = ProcessTarget.Server(port = 8080, portEnvVar = "MY_PORT")
        target.portEnvVar shouldBe "MY_PORT"
      }

      test("Worker defaults to FixedDelay") {
        val target = ProcessTarget.Worker()
        (target.readiness is ReadinessStrategy.FixedDelay) shouldBe true
      }

      test("Worker accepts custom readiness strategy") {
        val target = ProcessTarget.Worker(
          readiness = ReadinessStrategy.TcpPort(port = 9090)
        )
        (target.readiness is ReadinessStrategy.TcpPort) shouldBe true
      }
    }
  })
