plugins {}
dependencies {
    api(projects.lib.stoveTestingE2e)
    api(libs.elastic)
    api(libs.testcontainers.elasticsearch)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.arrow)
}

dependencies {
    testImplementation(libs.slf4j.simple)
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.testing.e2e.elasticsearch.Stove")
}
