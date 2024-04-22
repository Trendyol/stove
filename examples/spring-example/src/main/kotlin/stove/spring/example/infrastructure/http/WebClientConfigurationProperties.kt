package stove.spring.example.infrastructure.http

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

@ConfigurationProperties(prefix = "http-clients")
data class WebClientConfigurationProperties(
  var supplierHttp: ClientConfigurationProperty = ClientConfigurationProperty()
)

data class ClientConfigurationProperty(
  var url: String = "",
  val uri: URI = URI.create(url),
  var connectTimeout: Int = 0,
  var readTimeout: Long = 0
)
