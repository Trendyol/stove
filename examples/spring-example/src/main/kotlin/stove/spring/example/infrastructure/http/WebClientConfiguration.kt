package stove.spring.example.infrastructure.http

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.*
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.http.codec.ClientCodecConfigurer
import org.springframework.http.codec.json.JacksonJsonEncoder
import org.springframework.web.reactive.function.client.*
import reactor.netty.http.client.HttpClient
import stove.spring.example.infrastructure.ObjectMapperConfig
import tools.jackson.databind.json.JsonMapper
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
  fun webClientObjectMapper(): JsonMapper = ObjectMapperConfig.default()

  @Bean
  fun exchangeStrategies(webClientObjectMapper: JsonMapper): ExchangeStrategies = ExchangeStrategies
    .builder()
    .codecs { clientDefaultCodecsConfigurer: ClientCodecConfigurer ->
      clientDefaultCodecsConfigurer.defaultCodecs().maxInMemorySize(MAX_MEMORY_SIZE)
      clientDefaultCodecsConfigurer
        .defaultCodecs()
        .jacksonJsonEncoder(JacksonJsonEncoder(webClientObjectMapper, MediaType.APPLICATION_JSON))
      clientDefaultCodecsConfigurer
        .defaultCodecs()
        .jacksonJsonEncoder(JacksonJsonEncoder(webClientObjectMapper, MediaType.APPLICATION_JSON))
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
