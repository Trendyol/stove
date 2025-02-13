plugins {
  alias(libs.plugins.micronaut.library)
  alias(libs.plugins.google.ksp)
}

dependencies {
  api(projects.lib.stoveTestingE2e)
  api(libs.micronaut.core)
}

dependencies {
  testImplementation(libs.micronaut.test.kotest)
  testImplementation(libs.kotest.runner.junit5)
  testAnnotationProcessor(libs.micronaut.inject.java)
}

micronaut {
  version(libs.versions.micronaut.starter.get())
  processing {
    incremental(true)
    annotations("com.trendyol.stove.testing.*")
  }
}



