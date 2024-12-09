dependencies {
  api(projects.lib.stoveTestingE2e)
  implementation("io.micronaut:micronaut-core") // Temel Micronaut bağımlılığı
  implementation("io.micronaut.kotlin:micronaut-kotlin-runtime:4.5.0")
  testImplementation("io.micronaut.test:micronaut-test-kotest5:4.0.0")
  testImplementation("io.micronaut.test:micronaut-test-kotest5:4.0.0")
  testImplementation("io.kotest:kotest-runner-junit5:5.7.2")
}

repositories {
  mavenCentral()
  maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
}
