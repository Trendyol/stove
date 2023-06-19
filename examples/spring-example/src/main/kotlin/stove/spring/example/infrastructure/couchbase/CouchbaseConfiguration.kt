package stove.spring.example.infrastructure.couchbase

import com.couchbase.client.core.env.TimeoutConfig
import com.couchbase.client.java.ClusterOptions
import com.couchbase.client.java.ReactiveBucket
import com.couchbase.client.java.ReactiveCluster
import com.couchbase.client.java.ReactiveCollection
import com.couchbase.client.java.codec.JacksonJsonSerializer
import com.couchbase.client.java.env.ClusterEnvironment
import com.couchbase.client.java.json.JsonValueModule
import com.couchbase.client.metrics.micrometer.MicrometerMeter
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import stove.spring.example.infrastructure.ObjectMapperConfig
import java.time.Duration

@Configuration
@EnableConfigurationProperties(CouchbaseProperties::class)
class CouchbaseConfiguration(
    private val couchbaseProperties: CouchbaseProperties,
    private val meterRegistry: MeterRegistry
) {
    companion object {
        val objectMapper: ObjectMapper = ObjectMapperConfig.createObjectMapperWithDefaults()
            .registerModule(JsonValueModule())
    }

    @Primary
    @Bean(destroyMethod = "shutdown")
    fun clusterEnvironment(): ClusterEnvironment {
        val cbSerializer = JacksonJsonSerializer.create(objectMapper)
        return ClusterEnvironment
            .builder()
            .meter(MicrometerMeter.wrap(meterRegistry))
            .timeoutConfig {
                TimeoutConfig.builder()
                    .kvTimeout(Duration.ofMillis(couchbaseProperties.kvTimeout))
                    .connectTimeout(Duration.ofMillis(couchbaseProperties.connectTimeout))
                    .queryTimeout(Duration.ofMillis(couchbaseProperties.queryTimeout))
                    .viewTimeout(Duration.ofMillis(couchbaseProperties.viewTimeout))
            }
            .jsonSerializer(cbSerializer)
            .build()
    }

    @Primary
    @Bean(destroyMethod = "disconnect")
    fun cluster(clusterEnvironment: ClusterEnvironment): ReactiveCluster {
        val clusterOptions = ClusterOptions
            .clusterOptions(couchbaseProperties.username, couchbaseProperties.password)
            .environment(clusterEnvironment)

        return ReactiveCluster.connect(couchbaseProperties.hosts.joinToString(","), clusterOptions)
    }

    @Primary
    @Bean
    fun bucket(cluster: ReactiveCluster): ReactiveBucket {
        return cluster.bucket(couchbaseProperties.bucketName)
    }

    @Primary
    @Bean
    fun collection(bucket: ReactiveBucket): ReactiveCollection {
        return bucket.defaultCollection()
    }
}
