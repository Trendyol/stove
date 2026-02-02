import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm").version(libs.versions.kotlin)
  alias(libs.plugins.spotless)
  alias(libs.plugins.testLogger)
  alias(libs.plugins.kover)
  alias(libs.plugins.detekt)
  alias(libs.plugins.binaryCompatibilityValidator)
  alias(libs.plugins.maven.publish)
  idea
  java
}

group = "com.trendyol"
version = CI.version(project)

apiValidation {
  ignoredProjects += listOf(
    "ktor-example",
    "micronaut-example",
    "spring-example",
    "spring-4x-example",
    "spring-standalone-example",
    "spring-streams-example",
    "tests",
    "spring-test-fixtures",
    "spring-2x-kafka-tests",
    "spring-3x-kafka-tests",
    "spring-4x-kafka-tests",
    "spring-4x-tests",
    "spring-3x-tests",
    "spring-2x-tests",
    "ktor-di-tests",
    "ktor-koin-tests",
    "ktor-test-fixtures",
  )
}
kover {
  reports {
    filters {
      excludes {
        classes(
          "com.trendyol.stove.functional.*",
          "com.trendyol.stove.system.abstractions.*",
          "com.trendyol.stove.system.annotations.*",
          "com.trendyol.stove.serialization.*",
          "stove.spring.example.*",
          "stove.spring.standalone.example.*",
          "stove.spring.streams.example.*",
          "stove.ktor.example.*",
          "stove.micronaut.example.*",
        )
      }
    }
  }
}
val related = subprojects.of("lib", "spring", "examples", "ktor", "micronaut", "tests", "test-extensions", except = listOf("stove-bom"))
dependencies { related.forEach { kover(it) } }

subprojects.of("lib", "spring", "examples", "ktor", "micronaut", "tests", "test-extensions", except = listOf("stove-bom")) {
  apply {
    plugin("kotlin")
    plugin(rootProject.libs.plugins.spotless.get().pluginId)
    plugin(rootProject.libs.plugins.testLogger.get().pluginId)
    plugin(rootProject.libs.plugins.kover.get().pluginId)
    plugin(rootProject.libs.plugins.detekt.get().pluginId)
    plugin("idea")
  }

  val testImplementation by configurations
  val libs = rootProject.libs
  detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.from(rootProject.file("detekt.yml"))
  }
  dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.framework.engine)
    testImplementation(libs.kotest.assertions.core)
    detektPlugins(libs.detekt.formatting)
  }

  spotless {
    kotlin {
      ktlint(libs.ktlint.cli.get().version).setEditorConfigPath(rootProject.layout.projectDirectory.file(".editorconfig"))
      targetExclude("build/", "generated/", "out/")
      targetExcludeIfContentContains("generated")
      targetExcludeIfContentContainsRegex("generated.*")
    }
  }
  the<IdeaModel>().apply {
    module {
      isDownloadSources = true
      isDownloadJavadoc = true
    }
  }

  tasks {
    test {
      useJUnitPlatform()
      // Fail fast on CI to save time
      failFast = runningOnCI
      testlogger {
        setTheme("mocha")
        showStandardStreams = !runningOnCI
        showExceptions = true
        showCauses = true
      }
      reports {
        junitXml.required.set(true)
      }
      jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
    }
    kotlin {
      jvmToolchain(17)
      compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors = true
        freeCompilerArgs.addAll("-Xjsr305=strict")
      }
    }
  }
}

val publishedProjects = listOf(
  projects.lib.stoveBom.name,
  projects.lib.stove.name,
  projects.lib.stoveTracing.name,
  projects.lib.stoveCouchbase.name,
  projects.lib.stoveElasticsearch.name,
  projects.lib.stoveGrpc.name,
  projects.lib.stoveGrpcMock.name,
  projects.lib.stoveHttp.name,
  projects.lib.stoveKafka.name,
  projects.lib.stoveMongodb.name,
  projects.lib.stoveRdbms.name,
  projects.lib.stovePostgres.name,
  projects.lib.stoveMysql.name,
  projects.lib.stoveMssql.name,
  projects.lib.stoveWiremock.name,
  projects.lib.stoveRedis.name,
  projects.starters.ktor.stoveKtor.name,
  projects.starters.spring.stoveSpring.name,
  projects.starters.spring.stoveSpringKafka.name,
  projects.starters.micronaut.stoveMicronaut.name,
  projects.testExtensions.stoveExtensionsKotest.name,
  projects.testExtensions.stoveExtensionsJunit.name,
)

subprojects.of("lib", "spring", "ktor", "micronaut", "test-extensions", filter = { p -> publishedProjects.contains(p.name) && p.name != "stove-bom" }) {
  apply {
    plugin("java")
    plugin(rootProject.libs.plugins.maven.publish.pluginId)
  }

  mavenPublishing {
    coordinates(groupId = rootProject.group.toString(), artifactId = project.name, version = rootProject.version.toString())
    publishToMavenCentral()
    pom {
      name.set(project.name)
      description.set(project.properties["projectDescription"].toString())
      url.set(project.properties["projectUrl"].toString())
      licenses {
        license {
          name.set(project.properties["licence"].toString())
          url.set(project.properties["licenceUrl"].toString())
        }
      }
      developers {
        developer {
          id.set("osoykan")
          name.set("Oguzhan Soykan")
          email.set("oguzhan.soykan@trendyol.com")
        }
      }
      scm {
        connection.set("scm:git@github.com:Trendyol/stove.git")
        developerConnection.set("scm:git:ssh://github.com:Trendyol/stove.git")
        url.set(project.properties["projectUrl"].toString())
      }
    }
    signAllPublications()
  }

  java {
    withSourcesJar()
  }
}
