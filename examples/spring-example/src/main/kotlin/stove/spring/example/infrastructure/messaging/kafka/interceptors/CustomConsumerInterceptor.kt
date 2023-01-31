package stove.spring.example.infrastructure.messaging.kafka.interceptors

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.Header
import org.slf4j.MDC
import org.springframework.kafka.listener.ConsumerAwareRecordInterceptor
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/**
 * if we use RecordInterceptor<String, String> we should change it as ConsumerAwareRecordInterceptor<String, String> for the stove e2e testing.
 */
@Component
class CustomConsumerInterceptor : ConsumerAwareRecordInterceptor<String, String> {

    override fun intercept(
        record: ConsumerRecord<String, String>,
        consumer: Consumer<String, String>,
    ): ConsumerRecord<String, String>? {
        val contextMap = HashMap<String, String>()
        record.headers()
            .filter { it.key().lowercase().startsWith("x-") }
            .forEach { h: Header ->
                contextMap[h.key()] = String(h.value(), StandardCharsets.UTF_8)
            }
        MDC.setContextMap(contextMap)
        return record
    }
}
