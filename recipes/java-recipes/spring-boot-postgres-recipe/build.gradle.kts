plugins {
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.plugin)
  alias(libs.plugins.spring.dependencyManagement)
}

dependencies {
  implementation(libs.spring.boot.webflux)
  implementation(libs.spring.boot.autoconfigure)
  implementation(libs.spring.boot.kafka)
  implementation(libs.spring.boot.data.r2dbc)
  implementation(libs.postgresql.r2dbc)
  implementation(libs.postgresql)
  implementation(projects.shared.application)
  implementation(rootProject.projects.shared.domain)
  annotationProcessor(libs.spring.boot.annotationProcessor)
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
