dependencies {
  api(projects.lib.stoveTestingE2e)
  implementation(libs.micronaut.core)
  implementation(libs.micronaut.kotlin.runtime)
  testImplementation(libs.micronaut.test.kotest)
  testImplementation(libs.kotest.runner.junit5)
}

repositories {
  mavenCentral()
  maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
}
