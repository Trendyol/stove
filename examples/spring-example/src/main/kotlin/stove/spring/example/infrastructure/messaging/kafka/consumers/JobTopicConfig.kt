package stove.spring.example.infrastructure.messaging.kafka.consumers

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "kafka.consumers")
class ConsumerConfig(
    var enabled: Boolean = false,
    var groupId: String = "",
    var retryTopicSuffix: String = "",
    var errorTopicSuffix: String = "",
)

@Configuration
@ConfigurationProperties(prefix = "kafka.consumers.product-create")
class ProductCreateEventTopicConfig : TopicConfig()

abstract class TopicConfig(
    var topic: String = "",
    var retryTopic: String = "",
    var errorTopic: String = "",
)
