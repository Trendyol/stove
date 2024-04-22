package stove.spring.example.infrastructure.messaging.kafka.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.*

@ConfigurationProperties(prefix = "kafka")
data class KafkaProperties(
  var bootstrapServers: String = "",
  var acks: String = "1",
  var topicPrefix: String = "",
  var heartbeatInSeconds: Int = 30,
  var requestTimeout: String,
  var defaultApiTimeout: String,
  var compression: String = "zstd",
  var offset: String = "latest",
  var autoCreateTopics: Boolean = false,
  var secureKafka: Boolean = false
) {
  val maxProducerConsumerBytes = "4194304"

  fun createClientId() = UUID.randomUUID().toString().substring(0, 5)
}
