plugins {
  alias(libs.plugins.spring.boot.three)
  alias(libs.plugins.spring.plugin)
  alias(libs.plugins.spring.dependencyManagement)
  scala
}

dependencies {
  implementation(libs.scala2.library)
  implementation(libs.spring.boot.three.webflux)
  implementation(libs.spring.boot.three.autoconfigure)
  implementation(libs.spring.boot.three.kafka)
  annotationProcessor(libs.spring.boot.three.annotationProcessor)
}

dependencies {
  testImplementation(stoveLibs.stove)
  testImplementation(stoveLibs.stoveCouchbase)
  testImplementation(stoveLibs.stoveHttp)
  testImplementation(stoveLibs.stoveWiremock)
  testImplementation(stoveLibs.stoveKafka)
  testImplementation(stoveLibs.stoveSpring)
}
