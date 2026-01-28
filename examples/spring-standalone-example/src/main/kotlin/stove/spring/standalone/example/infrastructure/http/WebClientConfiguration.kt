package stove.spring.standalone.example.infrastructure.http

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.jackson.*
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.*

@Suppress("MagicNumber")
@Configuration
@EnableConfigurationProperties(WebClientConfigurationProperties::class)
class WebClientConfiguration(
  private val webClientConfigurationProperties: WebClientConfigurationProperties
) {
  @Bean
  fun supplierHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) {
      jackson(contentType = io.ktor.http.ContentType.Application.Json)
    }

    defaultRequest {
      url(webClientConfigurationProperties.supplierHttp.url)
    }

    engine {
      config {
        followRedirects(true)
        connectTimeout(java.time.Duration.ofSeconds(30))
        readTimeout(java.time.Duration.ofSeconds(30))
      }
    }

    expectSuccess = true
  }
}
