dependencies {
    api(projects.lib.stoveTestingE2e)
    api(libs.lettuce.core)
    api(libs.testcontainers.redis)
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.testing.e2e.redis.Stove")
}

