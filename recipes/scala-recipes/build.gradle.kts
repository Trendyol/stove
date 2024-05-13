plugins {
  java
  idea
  alias(libs.plugins.spotless)
}

subprojects {
  apply {
    plugin("java")
    plugin("idea")
    plugin(rootProject.libs.plugins.spotless.get().pluginId)
  }
  val libs = rootProject.libs
  sourceSets {
    @Suppress("LocalVariableName", "ktlint:standard:property-naming")
    val `test-e2e` by creating {
      compileClasspath += sourceSets.main.get().output
      runtimeClasspath += sourceSets.main.get().output
    }

    val testE2eImplementation by configurations.getting {
      extendsFrom(configurations.testImplementation.get())
    }
    configurations["testE2eRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())
  }

  idea {
    module {
      testSources.from(sourceSets[TestFolders.e2e].allSource.sourceDirectories)
      testResources.from(sourceSets[TestFolders.e2e].resources.sourceDirectories)
      isDownloadJavadoc = true
      isDownloadSources = true
    }
  }
  dependencies {
    implementation(rootProject.projects.shared.domain)
  }

  dependencies {
  }

  spotless {
    scala {
      scalafmt()
    }
  }

  task<Test>("e2eTest") {
    description = "Runs e2e tests."
    group = "verification"
    testClassesDirs = sourceSets[TestFolders.e2e].output.classesDirs
    classpath = sourceSets[TestFolders.e2e].runtimeClasspath

    useJUnitPlatform()
    reports {
      junitXml.required.set(true)
      html.required.set(true)
    }
  }
}
