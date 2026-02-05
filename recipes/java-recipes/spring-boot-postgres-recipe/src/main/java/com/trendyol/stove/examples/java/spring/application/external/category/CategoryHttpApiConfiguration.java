package com.trendyol.stove.examples.java.spring.application.external.category;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trendyol.stove.recipes.shared.application.ExternalApiConfiguration;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
@EnableConfigurationProperties(CategoryApiSpringConfiguration.class)
public class CategoryHttpApiConfiguration {

  @Bean
  public CategoryHttpApi categoryHttpApi(
      CategoryApiSpringConfiguration categoryApiConfiguration, ObjectMapper objectMapper) {
    return new CategoryHttpApiImpl(webClient(categoryApiConfiguration, objectMapper));
  }

  private WebClient webClient(
      ExternalApiConfiguration categoryApiConfiguration, ObjectMapper objectMapper) {
    var client = HttpClient.create()
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, categoryApiConfiguration.getTimeout())
        .doOnConnected(connection -> connection.addHandlerLast(
            new ReadTimeoutHandler(categoryApiConfiguration.getTimeout())));
    return WebClient.builder()
        .baseUrl(categoryApiConfiguration.getUrl())
        .clientConnector(new ReactorClientHttpConnector(client))
        .defaultRequest(r -> r.accept(MediaType.APPLICATION_JSON))
        .codecs(configurer -> {
          configurer
              .defaultCodecs()
              .jackson2JsonEncoder(
                  new Jackson2JsonEncoder(objectMapper, MediaType.APPLICATION_JSON));
          configurer
              .defaultCodecs()
              .jackson2JsonDecoder(
                  new Jackson2JsonDecoder(objectMapper, MediaType.APPLICATION_JSON));
        })
        .build();
  }
}
