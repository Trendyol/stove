dependencies {
  testImplementation(testFixtures(projects.testExtensions.stoveExtensionsCommon))
  api(projects.testExtensions.stoveExtensionsCommon)
  api(libs.junit.jupiter.api)
}

dependencies {
  testImplementation(projects.lib.stoveHttp)
  testImplementation(projects.lib.stoveWiremock)
  testImplementation(libs.kotest.runner.junit6)
  testImplementation(libs.kotest.assertions.core)
  testImplementation(libs.logback.classic)
  testImplementation(libs.junit.jupiter.params)
  testImplementation(libs.junit.platform.testkit)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testImplementation(testFixtures(projects.lib.stove))
}

kover {
  currentProject {
    sources {
      excludedSourceSets.addAll("test")
    }
  }
  reports {
    filters {
      excludes {
        classes(
          "*Test",
          "*Tests",
          "*Config"
        )
      }
    }
  }
}

// Deliberately failing fixtures are launched only by the contract test.
tasks.test { useJUnitPlatform { excludeTags("adapter-fixture") } }
