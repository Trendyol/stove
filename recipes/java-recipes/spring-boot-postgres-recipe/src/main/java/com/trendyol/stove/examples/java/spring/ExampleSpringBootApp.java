package com.trendyol.stove.examples.java.spring;

import java.util.function.Consumer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableKafka
public class ExampleSpringBootApp {
  public static void main(String[] args) {
    run(args, application -> {});
  }

  public static ConfigurableApplicationContext run(
      String[] args, Consumer<SpringApplication> applicationConsumer) {
    SpringApplication application = new SpringApplication(ExampleSpringBootApp.class);
    applicationConsumer.accept(application);
    return application.run(args);
  }
}
