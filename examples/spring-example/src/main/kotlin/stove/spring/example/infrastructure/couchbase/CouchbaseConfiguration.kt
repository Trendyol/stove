package stove.spring.example.infrastructure.couchbase

import com.couchbase.client.java.*
import com.couchbase.client.java.env.ClusterEnvironment
import com.couchbase.client.metrics.micrometer.MicrometerMeter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.*
import stove.spring.example.infrastructure.ObjectMapperConfig
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

@Configuration
@EnableConfigurationProperties(CouchbaseProperties::class)
class CouchbaseConfiguration(
  private val couchbaseProperties: CouchbaseProperties,
  private val meterRegistry: MeterRegistry
) {
  companion object {
    val objectMapper: JsonMapper = ObjectMapperConfig.default()
  }

  @Primary
  @Bean(destroyMethod = "shutdown")
  fun clusterEnvironment(): ClusterEnvironment {
    val cbSerializer = Jackson3JsonSerializer(objectMapper)
    return ClusterEnvironment
      .builder()
      .meter(MicrometerMeter.wrap(meterRegistry))
      .timeoutConfig {
        it
          .kvTimeout(Duration.ofMillis(couchbaseProperties.kvTimeout))
          .connectTimeout(Duration.ofMillis(couchbaseProperties.connectTimeout))
          .queryTimeout(Duration.ofMillis(couchbaseProperties.queryTimeout))
          .viewTimeout(Duration.ofMillis(couchbaseProperties.viewTimeout))
      }.jsonSerializer(cbSerializer)
      .build()
  }

  @Primary
  @Bean(destroyMethod = "disconnect")
  fun cluster(clusterEnvironment: ClusterEnvironment): ReactiveCluster {
    val clusterOptions =
      ClusterOptions
        .clusterOptions(couchbaseProperties.username, couchbaseProperties.password)
        .environment(clusterEnvironment)

    return ReactiveCluster.connect(couchbaseProperties.hosts.joinToString(","), clusterOptions)
  }

  @Primary
  @Bean
  fun bucket(cluster: ReactiveCluster): ReactiveBucket = cluster.bucket(couchbaseProperties.bucketName)

  @Primary
  @Bean
  fun collection(bucket: ReactiveBucket): ReactiveCollection = bucket.defaultCollection()
}
