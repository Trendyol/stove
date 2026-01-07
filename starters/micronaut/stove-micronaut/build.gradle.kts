plugins {
  alias(libs.plugins.micronaut.library)
  alias(libs.plugins.google.ksp)
}

dependencies {
  api(projects.lib.stove)
  api(libs.micronaut.core)
}

dependencies {
  testImplementation(projects.testExtensions.stoveExtensionsKotest)
  testImplementation(libs.micronaut.test.kotest)
  kspTest(platform(libs.micronaut.platform))
  kspTest(libs.micronaut.inject.kotlin)
}

micronaut {
  version(libs.versions.micronaut.platform.get())
  processing {
    incremental(true)
    annotations("com.trendyol.stove.*")
  }
}
