package com.stove.spring.example4x.e2e

import com.trendyol.stove.testing.e2e.*
import com.trendyol.stove.testing.e2e.http.*
import com.trendyol.stove.testing.e2e.kafka.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.wiremock.*
import io.kotest.core.config.AbstractProjectConfig
import org.slf4j.*
import tools.jackson.databind.json.JsonMapper

class Stove : AbstractProjectConfig() {
  private val logger: Logger = LoggerFactory.getLogger("WireMockMonitor")

  override suspend fun beforeProject(): Unit =
    TestSystem()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:8005"
          )
        }
        kafka {
          KafkaSystemOptions(
            ops = defaultKafkaOps(),
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
            stove.spring.example4x.run(parameters) {
              addTestDependencies {
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

  override suspend fun afterProject(): Unit = TestSystem.stop()
}
