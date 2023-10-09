package stove.spring.example.infrastructure.messaging.kafka.configuration

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

interface ConsumerSettings : MapBasedSettings

@Component
@EnableConfigurationProperties(KafkaProperties::class)
class DefaultConsumerSettings(
    val kafkaProperties: KafkaProperties
) : ConsumerSettings {
    @Value("\${kafka.config.thread-count.basic-listener}")
    private val basicListenerThreadCount: String = "100"

    /**
     * We gave some properties as parameterized from application yaml for the override of this param from the stove.
     * These are like below;
     * autoCreateTopics: we are sending as true this param for creating missing topics in initialize time.
     * heartbeatInSeconds: we should reduce heartbeat seconds the e2e environment, so we parameterized this field.
     * secureKafka: this is Kafka secure parameter we can set false in default yaml.
     *      If we want to use it for stage and prod yaml environment for adding secure Kafka configs set isSecure:true
     * offset: we should override this field as earliest for the stove e2e environment.
     */
    override fun settings(): Map<String, Any> {
        val props: MutableMap<String, Any> = HashMap()
        props[ConsumerConfig.CLIENT_ID_CONFIG] = kafkaProperties.createClientId()
        props[ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG] = kafkaProperties.autoCreateTopics
        props[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaProperties.bootstrapServers
        props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = kafkaProperties.offset
        props[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = true
        props[ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG] = ofSeconds(5)
        props[ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG] = ofSeconds(120)
        props[ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG] = ofSeconds(kafkaProperties.heartbeatInSeconds.toLong())
        props[ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG] = ofMinutes(5)
        props[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] = basicListenerThreadCount
        props[ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG] = kafkaProperties.defaultApiTimeout
        props[ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG] = kafkaProperties.requestTimeout
        // if we want to add secure Kafka config we can add these config inside of if (kafkaProperties.isSecure)
        return props
    }

    private fun ofSeconds(seconds: Long) = Duration.ofSeconds(seconds).toMillis().toInt()

    private fun ofMinutes(minutes: Long) = Duration.ofMinutes(minutes).toMillis().toInt()
}
