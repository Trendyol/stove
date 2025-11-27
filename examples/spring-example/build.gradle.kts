plugins {
  alias(libs.plugins.spring.plugin)
  alias(libs.plugins.spring.boot.four)
  idea
  application
}

dependencies {
  implementation(libs.spring.boot.four)
  implementation(libs.spring.boot.four.autoconfigure)
  implementation(libs.spring.boot.four.webflux)
  implementation(libs.spring.boot.four.actuator)
  annotationProcessor(libs.spring.boot.four.annotationProcessor)
  implementation(libs.spring.boot.four.kafka)
  implementation(libs.kotlinx.reactor)
  implementation(libs.kotlinx.core)
  implementation(libs.kotlinx.reactive)
  implementation(libs.couchbase.client)
  implementation(libs.couchbase.client.metrics)
  implementation(libs.kotlinx.slf4j)
}

dependencies {
  testImplementation(projects.stove.lib.stoveTestingE2eHttp)
  testImplementation(projects.stove.lib.stoveTestingE2eWiremock)
  testImplementation(projects.stove.lib.stoveTestingE2eCouchbase)
  testImplementation(projects.stove.lib.stoveTestingE2eElasticsearch)
  testImplementation(projects.stove.starters.spring.stoveSpringTestingE2e)
  testImplementation(projects.stove.starters.spring.stoveSpringTestingE2eKafka)
}

application { mainClass.set("stove.spring.example.ExampleAppkt") }
