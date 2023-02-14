@file:Suppress("UnstableApiUsage", "DSL_SCOPE_VIOLATION")

import org.jetbrains.dokka.gradle.DokkaMultiModuleTask

plugins {
    kotlin("jvm").version(libs.versions.kotlin)
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.palantirGitVersioning)
    id("stove-publishing") apply false
    id("coverage")
    java
}

group = "com.trendyol"
val versionDetails: groovy.lang.Closure<com.palantir.gradle.gitversion.VersionDetails> by extra
val details = versionDetails()
version = details.lastTag

allprojects {
    extra.set("dokka.outputDirectory", rootDir.resolve("docs"))
}

subprojectsOf("lib", "spring", "examples", "ktor") {
    apply {
        plugin("kotlin")
        plugin(rootProject.libs.plugins.ktlint.get().pluginId)
        plugin(rootProject.libs.plugins.dokka.get().pluginId)
    }

    val testImplementation by configurations
    val libs = rootProject.libs
    val testLibs = rootProject.testLibs

    dependencies {
        api(libs.arrow.core)
    }

    dependencies {
        testImplementation(kotlin("test"))
        testImplementation(testLibs.kotest.runner.junit5)
        testImplementation(testLibs.kotest.framework.api.jvm)
        testImplementation(testLibs.kotest.property.jvm)
    }

    this.tasks {
        test {
            useJUnitPlatform()
        }

        withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
            kotlinOptions.jvmTarget = "16"
            kotlinOptions.allWarningsAsErrors = true
        }
    }
}

val publishedProjects = listOf(
    "stove-testing-e2e",
    "stove-testing-e2e-couchbase",
    "stove-testing-e2e-elasticsearch",
    "stove-testing-e2e-http",
    "stove-testing-e2e-kafka",
    "stove-testing-e2e-rdbms-postgres",
    "stove-testing-e2e-wiremock",
    "stove-ktor-testing-e2e",
    "stove-spring-testing-e2e",
    "stove-spring-testing-e2e-kafka"
)

subprojectsOf("lib", "spring", "ktor", filter = { p -> publishedProjects.contains(p.name) }) {
    apply {
        plugin("java")
        plugin("stove-publishing")
    }

    java {
        withSourcesJar()
        withJavadocJar()
    }
}

tasks.withType<DokkaMultiModuleTask>().configureEach {
    outputDirectory.set(file(rootDir.resolve("docs/source")))
}

fun subprojectsOf(
    vararg parentProjects: String,
    action: Action<Project>,
): Unit = subprojects.filter { parentProjects.contains(it.parent?.name) }.forEach { action(it) }

fun subprojectsOf(
    vararg parentProjects: String,
    filter: (Project) -> Boolean,
    action: Action<Project>,
): Unit = subprojects.filter { parentProjects.contains(it.parent?.name) && filter(it) }.forEach { action(it) }
