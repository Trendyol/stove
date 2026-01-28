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
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.serialization.jackson.json)
  implementation(libs.spring.boot.three.actuator)
  annotationProcessor(libs.spring.boot.three.annotationProcessor)
  implementation(libs.spring.boot.three.kafka)
  implementation(libs.exposed.core)
  implementation(libs.exposed.jdbc)
  implementation(libs.exposed.java.time)
  implementation(libs.kotlinx.reactor)
  implementation(libs.kotlinx.core)
  implementation(libs.kotlinx.reactive)
  implementation(libs.postgresql)
  implementation(libs.jackson.kotlin)
  implementation(libs.kotlinx.slf4j)
  implementation(libs.hikari)
}

dependencies {
  testImplementation(projects.stove.testExtensions.stoveExtensionsKotest)
  testImplementation(projects.stove.lib.stoveHttp)
  testImplementation(projects.stove.lib.stoveWiremock)
  testImplementation(projects.stove.lib.stovePostgres)
  testImplementation(projects.stove.lib.stoveElasticsearch)
  testImplementation(projects.stove.starters.spring.stoveSpring)
  testImplementation(projects.stove.starters.spring.stoveSpringKafka)
}

application { mainClass.set("stove.spring.example.ExampleAppKt") }
