package stove.spring.example.infrastructure.http

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.http.codec.ClientCodecConfigurer
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.web.reactive.function.client.*
import reactor.netty.http.client.HttpClient
import stove.spring.example.infrastructure.ObjectMapperConfig
import java.util.concurrent.TimeUnit

@Configuration
@EnableConfigurationProperties(WebClientConfigurationProperties::class)
class WebClientConfiguration(
  private val webClientConfigurationProperties: WebClientConfigurationProperties
) {
  companion object {
    private const val MAX_MEMORY_SIZE = 50 * 1024 * 1024
  }

  @Bean
  fun supplierHttpClient(exchangeStrategies: ExchangeStrategies): WebClient =
    defaultWebClientBuilder(
      webClientConfigurationProperties.supplierHttp.url,
      webClientConfigurationProperties.supplierHttp.connectTimeout,
      webClientConfigurationProperties.supplierHttp.readTimeout
    ).exchangeStrategies(exchangeStrategies)
      .build()

  @Bean
  fun webClientObjectMapper(): ObjectMapper = ObjectMapperConfig
    .default()
    .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, false)

  @Bean
  fun exchangeStrategies(webClientObjectMapper: ObjectMapper): ExchangeStrategies = ExchangeStrategies
    .builder()
    .codecs { clientDefaultCodecsConfigurer: ClientCodecConfigurer ->
      clientDefaultCodecsConfigurer.defaultCodecs().maxInMemorySize(MAX_MEMORY_SIZE)
      clientDefaultCodecsConfigurer
        .defaultCodecs()
        .jackson2JsonEncoder(Jackson2JsonEncoder(webClientObjectMapper, MediaType.APPLICATION_JSON))
      clientDefaultCodecsConfigurer
        .defaultCodecs()
        .jackson2JsonDecoder(Jackson2JsonDecoder(webClientObjectMapper, MediaType.APPLICATION_JSON))
    }.build()

  private fun defaultWebClientBuilder(
    baseUrl: String,
    connectTimeout: Int,
    readTimeout: Long
  ): WebClient.Builder = WebClient
    .builder()
    .baseUrl(baseUrl)
    .clientConnector(
      ReactorClientHttpConnector(
        HttpClient
          .create()
          .followRedirect(true)
          .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout)
          .doOnConnected { conn ->
            conn.addHandlerLast(
              ReadTimeoutHandler(
                readTimeout,
                TimeUnit.MILLISECONDS
              )
            )
          }
      )
    )
}
