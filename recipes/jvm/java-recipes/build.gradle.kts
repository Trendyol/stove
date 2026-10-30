plugins {
  java
  kotlin("jvm") version libs.versions.kotlin
  idea
}

subprojects {
  apply {
    plugin("java")
    plugin("kotlin")
    plugin("idea")
  }
  val libs = rootProject.libs
  sourceSets {
    create(TestFolders.e2e) {
      compileClasspath += sourceSets.main.get().output
      runtimeClasspath += sourceSets.main.get().output
    }
  }

  configurations.getByName("testE2eImplementation") {
    extendsFrom(configurations.testImplementation.get())
  }
  configurations.getByName("testE2eRuntimeOnly").extendsFrom(configurations.runtimeOnly.get())

  idea {
    module {
      testSources.from(sourceSets[TestFolders.e2e].allSource.sourceDirectories)
      testResources.from(sourceSets[TestFolders.e2e].resources.sourceDirectories)
      isDownloadJavadoc = true
      isDownloadSources = true
    }
  }

  dependencies {
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
  }

  dependencies {
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
  }

  tasks.register<Test>("e2eTest") {
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
