dependencies {
  api(projects.lib.stoveTestingE2e)
  api(libs.lettuce.core)
  api(libs.testcontainers.redis)

  // Reason: https://github.com/redis/lettuce/issues/3317
  api(libs.micrometer.commons)
}

