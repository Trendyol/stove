package stove.spring.example.infrastructure.messaging.kafka.configuration

interface MapBasedSettings {
    fun settings(): Map<String, Any>
}
