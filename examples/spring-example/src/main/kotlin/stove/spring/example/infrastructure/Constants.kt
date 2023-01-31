package stove.spring.example.infrastructure

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.*
import org.slf4j.MDC

class Defaults {
    companion object {
        val HOST_NAME: String = try {
            InetAddress.getLocalHost().hostName
        } catch (e: UnknownHostException) {
            "stove-service-host"
        }

        const val AGENT_NAME = "stove-service"
        const val USER_EMAIL = "stove@trendyol.com"
    }
}

class Headers {
    companion object {
        const val USER_EMAIL_KEY: String = "X-UserEmail"
        const val CORRELATION_ID_KEY: String = "X-CorrelationId"
        const val AGENT_NAME_KEY = "X-AgentName"
        const val PUBLISHED_DATE_KEY = "X-PublishedDate"
        const val MESSAGE_ID_KEY = "X-MessageId"
        const val HOST_KEY = "X-Host"
        const val EVENT_TYPE = "X-EventType"

        fun getOrDefault(
            key: String,
            defaultValue: String = Defaults.USER_EMAIL,
        ): String {
            return try {
                MDC.get(key) ?: MDC.get(key.lowercase()) ?: MDC.get(key.uppercase()) ?: defaultValue
            } catch (exception: IllegalStateException) {
                defaultValue
            }
        }
    }
}
