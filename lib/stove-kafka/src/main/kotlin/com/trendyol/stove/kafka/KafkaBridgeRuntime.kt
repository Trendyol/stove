package com.trendyol.stove.kafka

import com.trendyol.stove.serialization.StoveSerde
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val bridgePortDiscoveryLock = Any()

/**
 * Preserves the original same-JVM discovery path for applications that configure only the
 * interceptor class. Keyed systems cannot safely use a process-global fallback and must use the
 * authoritative client properties exposed by their own system.
 */
internal fun exposeBridgePortForInJvmDiscovery(
  keyName: String?,
  port: Int
): AutoCloseable {
  if (keyName != null) return AutoCloseable {}

  val exposedPort = port.toString()
  val previousPort = synchronized(bridgePortDiscoveryLock) {
    System.getProperty(STOVE_KAFKA_BRIDGE_PORT).also {
      System.setProperty(STOVE_KAFKA_BRIDGE_PORT, exposedPort)
    }
  }
  var closed = false
  return AutoCloseable {
    synchronized(bridgePortDiscoveryLock) {
      if (closed) return@synchronized
      closed = true
      // Do not overwrite a value deliberately changed by the application after startup.
      if (System.getProperty(STOVE_KAFKA_BRIDGE_PORT) == exposedPort) {
        if (previousPort == null) {
          System.clearProperty(STOVE_KAFKA_BRIDGE_PORT)
        } else {
          System.setProperty(STOVE_KAFKA_BRIDGE_PORT, previousPort)
        }
      }
    }
  }
}

/**
 * Kafka client properties understood by [com.trendyol.stove.kafka.intercepting.StoveKafkaBridge].
 *
 * Stove adds these properties to the corresponding producer and consumer configuration so keyed
 * Kafka systems route to their own observer without relying on process-global state.
 */
internal object KafkaBridgeConfig {
  const val BRIDGE_ID_CONFIG: String = "stove.kafka.bridge.id"
  const val BRIDGE_PORT_CONFIG: String = "stove.kafka.bridge.port"
}

/** Internal address and identity of the observer belonging to one [KafkaSystem]. */
internal data class KafkaBridgeEndpoint(
  val id: String,
  val port: Int
) {
  val clientProperties: Map<String, Any>
    get() = mapOf(
      KafkaBridgeConfig.BRIDGE_ID_CONFIG to id,
      KafkaBridgeConfig.BRIDGE_PORT_CONFIG to port.toString()
    )

  /** String configuration entries suitable for Stove's application configuration hand-off. */
  fun configurationEntries(): List<String> = clientProperties.map { (key, value) -> "$key=$value" }
}

internal class KafkaBridgeRuntime(
  private val serde: StoveSerde<Any, ByteArray>,
  keyName: String?
) : AutoCloseable {
  val id: String = UUID.randomUUID().toString()
  val systemId: String = keyName ?: "default"
  val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  lateinit var endpoint: KafkaBridgeEndpoint
    private set

  init {
    KafkaBridgeRuntimeRegistry.register(id, serde)
  }

  fun attach(port: Int) {
    endpoint = KafkaBridgeEndpoint(id = id, port = port)
  }

  val clientProperties: Map<String, Any>
    get() = endpoint.clientProperties

  override fun close() {
    KafkaBridgeRuntimeRegistry.unregister(id)
    scope.cancel()
  }
}

internal object KafkaBridgeRuntimeRegistry {
  private val serdes = ConcurrentHashMap<String, StoveSerde<Any, ByteArray>>()

  fun register(
    id: String,
    serde: StoveSerde<Any, ByteArray>
  ) {
    serdes[id] = serde
  }

  fun unregister(id: String) {
    serdes.remove(id)
  }

  fun serde(id: String): StoveSerde<Any, ByteArray>? = serdes[id]
}
