plugins {
  alias(libs.plugins.spring.plugin)
  alias(libs.plugins.spring.boot.three)
  idea
  application
}

dependencies {
  implementation(libs.spring.boot.three)
  implementation(libs.spring.boot.three.autoconfigure)
  implementation(libs.spring.boot.three.webflux)
  implementation(libs.spring.boot.three.actuator)
  annotationProcessor(libs.spring.boot.three.annotationProcessor)
  implementation(libs.spring.boot.three.kafka)
  implementation(libs.kotlinx.reactor)
  implementation(libs.kotlinx.core)
  implementation(libs.kotlinx.reactive)
  implementation(libs.couchbase.client)
  implementation(libs.couchbase.client.metrics)
  implementation(libs.jackson.kotlin)
  implementation(libs.kotlinx.slf4j)
}

dependencies {
  testImplementation(projects.stove.lib.stoveTestingE2eHttp)
  testImplementation(projects.stove.lib.stoveTestingE2eWiremock)
  testImplementation(projects.stove.lib.stoveTestingE2eCouchbase)
  testImplementation(projects.stove.lib.stoveTestingE2eElasticsearch)
  testImplementation(projects.stove.lib.stoveTestingE2eKafka)
  testImplementation(projects.stove.starters.spring.stoveSpringTestingE2e)
}

application { mainClass.set("stove.spring.standalone.example.ExampleAppkt") }
