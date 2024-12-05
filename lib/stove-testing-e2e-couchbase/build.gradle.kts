dependencies {
    api(projects.lib.stoveTestingE2e)
    api(libs.couchbase.kotlin)
    api(libs.testcontainers.couchbase)
}

dependencies {
    testImplementation(libs.slf4j.simple)
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.testing.e2e.couchbase.Stove")
}
