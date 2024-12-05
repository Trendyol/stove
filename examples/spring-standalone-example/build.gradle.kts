plugins {
  alias(libs.plugins.spring.plugin)
  alias(libs.plugins.spring.boot)
  idea
  application
}

dependencies {
  implementation(libs.spring.boot.get3x())
  implementation(libs.spring.boot.get3x().autoconfigure)
  implementation(libs.spring.boot.get3x().webflux)
  implementation(libs.spring.boot.get3x().actuator)
  annotationProcessor(libs.spring.boot.get3x().annotationProcessor)
  implementation(libs.spring.boot.get3x().kafka)
  implementation(libs.kotlinx.reactor)
  implementation(libs.kotlinx.core)
  implementation(libs.kotlinx.reactive)
  implementation(libs.couchbase.client)
  implementation(libs.couchbase.client.metrics)
  implementation(libs.jackson.kotlin)
  implementation(libs.kotlinx.slf4j)
}

dependencies {
  testImplementation(libs.kotest.property)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(projects.stove.lib.stoveTestingE2eHttp)
  testImplementation(projects.stove.lib.stoveTestingE2eWiremock)
  testImplementation(projects.stove.lib.stoveTestingE2eCouchbase)
  testImplementation(projects.stove.lib.stoveTestingE2eElasticsearch)
  testImplementation(projects.stove.lib.stoveTestingE2eKafka)
  testImplementation(projects.stove.starters.spring.stoveSpringTestingE2e)
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.stove.spring.standalone.example.e2e.Stove")
}

application { mainClass.set("stove.spring.standalone.example.ExampleAppkt") }
