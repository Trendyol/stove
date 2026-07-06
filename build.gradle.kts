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
  alias(libs.plugins.micronaut.application) apply false
  alias(libs.plugins.micronaut.library) apply false
  alias(libs.plugins.micronaut.aot) apply false
  alias(libs.plugins.google.ksp) apply false
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
    "quarkus-example",
    "ktor-di-tests",
    "ktor-koin-tests",
    "ktor-test-fixtures",
    "stove-tracing-gradle-plugin",
    "stove-dashboard-api",
    "stove-dashboard",
    "stove-micronaut",
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
          "stove.quarkus.example.*",
          "stove.micronaut.example.*",
        )
      }
    }
  }
}
val related = subprojects.of("lib", "spring", "examples", "ktor", "quarkus", "micronaut", "container", "process", "tests", "test-extensions", except = listOf("stove-bom"))
dependencies { related.forEach { kover(it) } }

subprojects.of("lib", "spring", "examples", "ktor", "quarkus", "micronaut", "container", "process", "tests", "test-extensions", except = listOf("stove-bom")) {
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
    testImplementation(libs.kotest.runner.junit6)
    testImplementation(libs.kotest.framework.engine)
    testImplementation(libs.kotest.assertions.core)
    detektPlugins(libs.detekt.formatting)
  }

  spotless {
    kotlin {
      target("src/**/*.kt")
      ktlint(libs.ktlint.cli.get().version)
        .setEditorConfigPath(rootProject.layout.projectDirectory.file(".editorconfig"))
        .editorConfigOverride(
          mapOf(
            "ktlint_standard_kdoc" to "disabled",
            "ktlint_standard_class-signature" to "disabled"
          )
        )
      targetExclude("build/**", "**/build/**", "generated/**", "**/generated/**", "out/**", "**/out/**")
      targetExcludeIfContentContains("generated")
      targetExcludeIfContentContainsRegex("generated.*")
    }

    kotlinGradle {
      target("*.gradle.kts")
      ktlint(libs.ktlint.cli.get().version)
        .setEditorConfigPath(rootProject.layout.projectDirectory.file(".editorconfig"))
        .editorConfigOverride(
          mapOf(
            "ktlint_standard_kdoc" to "disabled",
            "ktlint_standard_class-signature" to "disabled"
          )
        )
      targetExclude("build/**", "**/build/**", "generated/**", "**/generated/**", "out/**", "**/out/**")
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

    if (JavaVersion.current().isCompatibleWith(JavaVersion.toVersion("25"))) {
      withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        enabled = false
      }
    } else {
      withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "21"
      }
    }

    kotlin {
      val java25Projects = setOf("stove-micronaut", "micronaut-example")
      val targetJvm = if (project.name in java25Projects) 25 else 17
      jvmToolchain(targetJvm)
      compilerOptions {
        val targetJvmTarget = if (project.name in java25Projects) JvmTarget.JVM_25 else JvmTarget.JVM_17
        jvmTarget.set(targetJvmTarget)
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
  projects.lib.stoveCassandra.name,
  projects.lib.stoveDashboard.name,
  projects.lib.stoveDashboardApi.name,
  projects.starters.ktor.stoveKtor.name,
  projects.starters.quarkus.stoveQuarkus.name,
  projects.starters.spring.stoveSpring.name,
  projects.starters.spring.stoveSpringKafka.name,
  projects.starters.micronaut.stoveMicronaut.name,
  projects.starters.container.stoveContainer.name,
  projects.starters.process.stoveProcess.name,
  projects.testExtensions.stoveExtensionsKotest.name,
  projects.testExtensions.stoveExtensionsJunit.name,
  projects.plugins.stoveTracingGradlePlugin.name,
)

subprojects.of("lib", "spring", "ktor", "quarkus", "micronaut", "container", "process", "test-extensions", "plugins", filter = { p -> publishedProjects.contains(p.name) && p.name != "stove-bom" }) {
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
    if (project.hasSigningKey) signAllPublications()
  }

  java {
    withSourcesJar()
  }
}
