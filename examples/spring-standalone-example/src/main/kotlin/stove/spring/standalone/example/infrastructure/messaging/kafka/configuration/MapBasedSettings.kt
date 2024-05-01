package stove.spring.standalone.example.infrastructure.messaging.kafka.configuration

interface MapBasedSettings {
  fun settings(): Map<String, Any>
}
