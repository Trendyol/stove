plugins {
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.plugin)
  alias(libs.plugins.spring.dependencyManagement)
  scala
}

dependencies {
  implementation(libs.scala2.library)
  implementation(libs.spring.boot.webflux)
  implementation(libs.spring.boot.autoconfigure)
  implementation(libs.spring.boot.kafka)
  implementation(libs.couchbase.client)
  annotationProcessor(libs.spring.boot.annotationProcessor)
}

dependencies {
  testImplementation(stoveLibs.stove)
  testImplementation(stoveLibs.stoveCouchbase)
  testImplementation(stoveLibs.stoveHttp)
  testImplementation(stoveLibs.stoveWiremock)
  testImplementation(stoveLibs.stoveKafka)
  testImplementation(stoveLibs.stoveSpring)
}
