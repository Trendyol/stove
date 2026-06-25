plugins {
  alias(libs.plugins.spring.boot.three)
  alias(libs.plugins.spring.plugin)
  alias(libs.plugins.spring.dependencyManagement)
}

dependencies {
  implementation(libs.spring.boot.three.webflux)
  implementation(libs.spring.boot.three.autoconfigure)
  implementation(libs.spring.boot.three.kafka)
  implementation(libs.spring.boot.three.data.r2dbc)
  implementation(libs.postgresql.r2dbc)
  implementation(libs.postgresql)
  implementation(projects.shared.application)
  implementation(rootProject.projects.shared.domain)
  annotationProcessor(libs.spring.boot.three.annotationProcessor)
}

dependencies {
  testImplementation(stoveLibs.stove)
  testImplementation(stoveLibs.stovePostgres)
  testImplementation(stoveLibs.stoveHttp)
  testImplementation(stoveLibs.stoveWiremock)
  testImplementation(stoveLibs.stoveKafka)
  testImplementation(stoveLibs.stoveSpring)
  testImplementation(libs.testcontainers.kafka)
}
