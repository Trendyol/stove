@file:Suppress("UnstableApiUsage", "DSL_SCOPE_VIOLATION")

import org.jetbrains.dokka.gradle.DokkaMultiModuleTask

group = "com.trendyol"
version = "0.0.7-SNAPSHOT"

plugins {
    kotlin("jvm").version(libs.versions.kotlin)
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint)
    id("stove-publishing") apply false
    id("coverage")
    java
}

allprojects {
    extra.set("dokka.outputDirectory", rootDir.resolve("docs"))
}

subprojectsOf("lib", "spring", "examples") {
    apply {
        plugin("kotlin")
        plugin("stove-publishing")
        plugin("java")
        plugin(rootProject.libs.plugins.ktlint.get().pluginId)
        plugin(rootProject.libs.plugins.dokka.get().pluginId)
    }

    java {
        withSourcesJar()
        withJavadocJar()
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

tasks.withType<DokkaMultiModuleTask>().configureEach {
    outputDirectory.set(file(rootDir.resolve("docs/source")))
}

fun subprojectsOf(
    vararg parentProjects: String,
    action: Action<Project>,
): Unit = subprojects.filter { parentProjects.contains(it.parent?.name) }.forEach { action(it) }
