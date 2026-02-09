plugins {}

val elasticsearchTestTag =
  providers
    .systemProperty("elasticsearchTestTag")
    .orElse(providers.environmentVariable("ELASTICSEARCH_TEST_TAG"))
    .orElse("8.9.0")

dependencies {
  api(projects.lib.stove)
  api(libs.elastic)
  api(libs.elastic.rest.client)
  api(libs.testcontainers.elasticsearch)
  implementation(libs.jackson.databind)
}

dependencies {
  testImplementation(project(":test-extensions:stove-extensions-kotest"))
  testImplementation(libs.slf4j.simple)
}

val testWithProvided = tasks.register<Test>("testWithProvided") {
  group = "verification"
  description = "Runs tests with an externally provided Elasticsearch instance"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform()
  systemProperty("useProvided", "true")
  val tag = elasticsearchTestTag.get()
  systemProperty("elasticsearchTestTag", tag)
  doFirst {
    println("Starting Elasticsearch tests with provided instance and tag=$tag...")
  }
}

tasks.withType<Test>().configureEach {
  systemProperty("elasticsearchTestTag", elasticsearchTestTag.get())
}

tasks.test.configure {
  dependsOn(testWithProvided)
}
