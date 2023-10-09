package com.trendyol.stove.testing.e2e.elasticsearch

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.trendyol.stove.functional.Reflect
import org.testcontainers.elasticsearch.ElasticsearchContainer
import java.util.*
import javax.net.ssl.SSLContext

data class ElasticsearchExposedCertificate(
    val bytes: ByteArray
) {
    @get:JsonIgnore
    @set:JsonIgnore
    var sslContext: SSLContext = SSLContext.getDefault()
        internal set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ElasticsearchExposedCertificate

        if (!bytes.contentEquals(other.bytes)) return false
        if (sslContext != other.sslContext) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + sslContext.hashCode()
        return result
    }

    companion object {
        @JsonCreator
        @JvmStatic
        fun create(
            @JsonProperty bytes: ByteArray
        ): ElasticsearchExposedCertificate {
            val container = ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:latest")
            Reflect(container) {
                on<Optional<ByteArray>>("caCertAsBytes").then(Optional.of(bytes))
            }
            return ElasticsearchExposedCertificate(bytes).apply {
                sslContext = container.createSslContextFromCa()
            }
        }
    }
}
