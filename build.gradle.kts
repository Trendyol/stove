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
    "spring-standalone-example",
    "spring-streams-example"
  )
}
kover {
  reports {
    filters {
      excludes {
        classes(
          "com.trendyol.stove.functional.*",
          "com.trendyol.stove.testing.e2e.system.abstractions.*",
          "com.trendyol.stove.testing.e2e.system.annotations.*",
          "com.trendyol.stove.testing.e2e.serialization.*",
          "com.trendyol.stove.testing.e2e.standalone.*",
          "com.trendyol.stove.testing.e2e.streams.*",
          "stove.spring.example.*",
          "stove.spring.standalone.example.*",
          "stove.spring.streams.example.*",
          "stove.ktor.example.*"
        )
      }
    }
  }
}
val related = subprojects.of("lib", "spring", "examples", "ktor", "micronaut")
dependencies { related.forEach { kover(it) } }

subprojects.of("lib", "spring", "examples", "ktor", "micronaut") {
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
  dependencies {
    api(libs.arrow.core)
  }

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
  projects.lib.stoveTestingE2e.name,
  projects.lib.stoveTestingE2eCouchbase.name,
  projects.lib.stoveTestingE2eElasticsearch.name,
  projects.lib.stoveTestingE2eHttp.name,
  projects.lib.stoveTestingE2eKafka.name,
  projects.lib.stoveTestingE2eMongodb.name,
  projects.lib.stoveTestingE2eRdbms.name,
  projects.lib.stoveTestingE2eRdbmsMssql.name,
  projects.lib.stoveTestingE2eRdbmsPostgres.name,
  projects.lib.stoveTestingE2eWiremock.name,
  projects.lib.stoveTestingE2eRedis.name,
  projects.starters.ktor.stoveKtorTestingE2e.name,
  projects.starters.spring.stoveSpringTestingE2e.name,
  projects.starters.spring.stoveSpringTestingE2eKafka.name,
  projects.starters.micronaut.stoveMicronautTestingE2e.name,
)

subprojects.of("lib", "spring", "ktor", "micronaut", filter = { p -> publishedProjects.contains(p.name) }) {
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
