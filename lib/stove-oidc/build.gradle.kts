dependencies {
  api(projects.lib.stove)
  api(libs.ktor.client.core)
  api(libs.ktor.client.cio)
  api(libs.nav.oidc)
  api(libs.keycloak.testcontainers)
  api(libs.keycloak.admin)
  implementation(libs.nimbus.jose)
  implementation(libs.jackson.databind)
  testImplementation(libs.logback.classic)
  testImplementation(projects.lib.stoveHttp)
}
