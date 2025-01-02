package stove.micronaut.example.infrastructure.couchbase

import com.couchbase.client.java.*
import com.couchbase.client.java.Collection
import com.couchbase.client.java.codec.JacksonJsonSerializer
import com.couchbase.client.java.env.ClusterEnvironment
import com.couchbase.client.java.json.JsonValueModule
import com.fasterxml.jackson.databind.ObjectMapper
import io.micronaut.context.annotation.*
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import java.time.Duration

@Factory
class CouchbaseConfiguration(
  private val couchbaseProperties: CouchbaseProperties
) {
  companion object {
    val objectMapper: ObjectMapper =
      ObjectMapper()
        .findAndRegisterModules()
        .registerModule(JsonValueModule())
  }

  @Primary
  @Context
  fun clusterEnvironment(): ClusterEnvironment {
    val cbSerializer = JacksonJsonSerializer.create(objectMapper)
    return ClusterEnvironment
      .builder()
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
  @Singleton
  fun cluster(clusterEnvironment: ClusterEnvironment): Cluster {
    val clusterOptions = ClusterOptions
      .clusterOptions(couchbaseProperties.username, couchbaseProperties.password)
      .environment(clusterEnvironment)

    return Cluster.connect(couchbaseProperties.hosts.joinToString(","), clusterOptions)
  }

  @Primary
  @Singleton
  fun bucket(cluster: Cluster): Bucket = cluster.bucket(couchbaseProperties.bucketName)

  @Primary
  @Singleton
  fun productCouchbaseCollection(bucket: Bucket): Collection = bucket.defaultCollection()

  @PreDestroy
  fun cleanup(cluster: Cluster, clusterEnvironment: ClusterEnvironment) {
    cluster.disconnect()
    clusterEnvironment.shutdown()
  }
}
