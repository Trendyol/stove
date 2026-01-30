package com.stove.spring.example4x.e2e

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.http.*
import com.trendyol.stove.kafka.*
import com.trendyol.stove.spring.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.tracing.*
import com.trendyol.stove.wiremock.*
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import org.slf4j.*
import stove.spring.example4x.run
import tools.jackson.databind.json.JsonMapper

class StoveConfig : AbstractProjectConfig() {
  override val extensions: List<Extension> = listOf(StoveKotestExtension())

  private val logger: Logger = LoggerFactory.getLogger("WireMockMonitor")

  override suspend fun beforeProject(): Unit =
    Stove()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:8005"
          )
        }
        kafka {
          KafkaSystemOptions(
            containerOptions = KafkaContainerOptions(tag = "7.8.1"),
            configureExposedConfiguration = {
              listOf(
                "kafka.bootstrapServers=${it.bootstrapServers}",
                "kafka.groupId=spring-4x-example"
              )
            }
          )
        }
        bridge()

        // Enable tracing - starts OTLP gRPC receiver on port 4317
        // Service name is automatically extracted from incoming spans (set by OTel agent)
        tracing {
          enableSpanReceiver()
        }

        wiremock {
          WireMockSystemOptions(
            port = 7080,
            removeStubAfterRequestMatched = true,
            afterRequest = { e, _ ->
              logger.info(e.request.toString())
            }
          )
        }
        springBoot(
          runner = { parameters ->
            // The application will be auto-instrumented by OTel agent
            // configured in build.gradle.kts tasks.test { }
            run(parameters) {
              addTestDependencies4x {
                registerBean<TestSystemKafkaInterceptor<*, *>>(primary = true)
                registerBean {
                  val jsonMapper = this.bean<JsonMapper>()
                  StoveJackson3ThroughIfStringSerde(jsonMapper)
                }
              }
            }
          },
          withParameters = listOf(
            "server.port=8005",
            "logging.level.root=info",
            "logging.level.org.springframework.web=info",
            "spring.profiles.active=default",
            "kafka.heartbeatInSeconds=2",
            "kafka.offset=earliest"
          )
        )
      }.run()

  override suspend fun afterProject(): Unit = Stove.stop()
}
