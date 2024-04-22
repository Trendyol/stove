package com.trendyol.stove.testing.e2e.elasticsearch

import com.fasterxml.jackson.annotation.*
import org.testcontainers.elasticsearch.ElasticsearchContainer
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.*

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

    return bytes.contentEquals(other.bytes)
  }

  override fun hashCode(): Int = bytes.contentHashCode()

  companion object {
    @JsonCreator
    @JvmStatic
    fun create(
      @JsonProperty bytes: ByteArray
    ): ElasticsearchExposedCertificate = ElasticsearchExposedCertificate(bytes).apply {
      sslContext = createSslContextFromCa(bytes)
    }

    /**
     * An SSL context based on the self-signed CA, so that using this SSL Context allows to connect to the Elasticsearch service
     * @return a customized SSL Context
     * @see ElasticsearchContainer.createSslContextFromCa
     */
    @Suppress("TooGenericExceptionCaught", "TooGenericExceptionThrown")
    private fun createSslContextFromCa(bytes: ByteArray): SSLContext = try {
      val factory = CertificateFactory.getInstance("X.509")
      val trustedCa = factory.generateCertificate(
        ByteArrayInputStream(bytes)
      )
      val trustStore = KeyStore.getInstance("pkcs12")
      trustStore.load(null, null)
      trustStore.setCertificateEntry("ca", trustedCa)

      val sslContext = SSLContext.getInstance("TLSv1.3")
      val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      trustManagerFactory.init(trustStore)
      sslContext.init(null, trustManagerFactory.trustManagers, null)
      sslContext
    } catch (e: Exception) {
      throw RuntimeException(e)
    }
  }
}
