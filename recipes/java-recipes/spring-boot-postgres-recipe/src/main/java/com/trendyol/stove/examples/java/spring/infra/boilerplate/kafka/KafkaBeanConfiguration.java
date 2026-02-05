package com.trendyol.stove.examples.java.spring.infra.boilerplate.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaBeanConfiguration {
  private final Logger logger = org.slf4j.LoggerFactory.getLogger(KafkaBeanConfiguration.class);

  @Bean
  @ConfigurationProperties(prefix = "kafka")
  public KafkaConfiguration kafkaConfiguration() {
    return new KafkaConfiguration();
  }

  @Bean
  public TopicResolver topicResolver(KafkaConfiguration kafkaConfiguration) {
    return new TopicResolver(kafkaConfiguration);
  }

  @Bean
  public Properties consumerProperties(KafkaConfiguration kafkaConfiguration) {
    Properties properties = new Properties();
    properties.put(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfiguration.getBootstrapServers());
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaConfiguration.getGroupId());
    properties.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int)
        Duration.ofSeconds(kafkaConfiguration.getRequestTimeoutSeconds()).toMillis());
    properties.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, (int)
        Duration.ofSeconds(kafkaConfiguration.getHeartbeatIntervalSeconds()).toMillis());
    properties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, (int)
        Duration.ofSeconds(kafkaConfiguration.getSessionTimeoutSeconds()).toMillis());
    properties.put(
        ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, kafkaConfiguration.isAutoCreateTopics());
    properties.put(
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, kafkaConfiguration.getAutoOffsetReset());

    properties.put(
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    properties.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class.getName());
    properties.put(
        ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class.getName());
    properties.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
    properties.put(JsonDeserializer.VALUE_DEFAULT_TYPE, Object.class.getName());

    properties.put(
        ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG, kafkaConfiguration.flattenInterceptorClasses());
    logger.info("Kafka consumer properties: {}", properties);
    return properties;
  }

  @Bean
  public Properties producerProperties(KafkaConfiguration kafkaConfiguration) {
    Properties properties = new Properties();
    properties.put(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfiguration.getBootstrapServers());
    properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class.getName());
    properties.put(
        ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, kafkaConfiguration.flattenInterceptorClasses());
    return properties;
  }

  @Bean
  public KafkaTemplate<?, ?> kafkaTemplate(KafkaConfiguration kafkaConfiguration) {
    return new KafkaTemplate<>(new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(
        toMap(producerProperties(kafkaConfiguration))));
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<?, ?> kafkaListenerContainerFactory(
      KafkaConfiguration kafkaConfiguration, ObjectMapper objectMapper) {
    ConcurrentKafkaListenerContainerFactory<?, ?> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(10, 1)));
    factory.setRecordMessageConverter(
        new org.springframework.kafka.support.converter.JsonMessageConverter(objectMapper));
    factory.setConsumerFactory(new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(
        toMap(consumerProperties(kafkaConfiguration))));
    return factory;
  }

  private Map<String, Object> toMap(Properties properties) {
    return properties.entrySet().stream()
        .collect(
            java.util.stream.Collectors.toMap(e -> e.getKey().toString(), Map.Entry::getValue));
  }
}
