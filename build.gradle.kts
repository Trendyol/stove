import org.jetbrains.dokka.gradle.DokkaMultiModuleTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm").version(libs.versions.kotlin)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.gitVersioning)
    id(libs.plugins.jacocoReportAggregation.get().pluginId)
    id(libs.plugins.jacoco.get().pluginId)
    id("stove-publishing") apply false
    id("reporting")
    alias(testLibs.plugins.testLogger)
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
        plugin(rootProject.libs.plugins.kotlinter.get().pluginId)
        plugin(rootProject.libs.plugins.dokka.get().pluginId)
        plugin(rootProject.libs.plugins.jacoco.get().pluginId)
        plugin(rootProject.libs.plugins.jacocoReportAggregation.get().pluginId)
        plugin(rootProject.testLibs.plugins.testLogger.get().pluginId)
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

    tasks {
        test {
            dependsOn(formatKotlin)
            useJUnitPlatform()
            testlogger {
                setTheme("mocha")
                this.showStandardStreams = true
            }
            reports {
                junitXml.required.set(true)
                html.required.set(true)
            }
        }

        jacocoTestReport {
            dependsOn(test)
            reports {
                xml.required.set(true)
                csv.required.set(false)
                html.required.set(true)
            }
        }

        kotlin {
            jvmToolchain(17)
        }

        withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
            dependsOn(formatKotlin, lintKotlin)
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                allWarningsAsErrors = true
            }
        }
    }
}

val publishedProjects = listOf(
    "stove-testing-e2e",
    "stove-testing-e2e-couchbase",
    "stove-testing-e2e-elasticsearch",
    "stove-testing-e2e-http",
    "stove-testing-e2e-kafka",
    "stove-testing-e2e-mongodb",
    "stove-testing-e2e-rdbms",
    "stove-testing-e2e-rdbms-postgres",
    "stove-testing-e2e-rdbms-mssql",
    "stove-testing-e2e-wiremock",
    "stove-testing-e2e-redis",
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
    action: Action<Project>
): Unit = subprojects.filter { parentProjects.contains(it.parent?.name) }.forEach { action(it) }

fun subprojectsOf(
    vararg parentProjects: String,
    filter: (Project) -> Boolean,
    action: Action<Project>
): Unit = subprojects.filter { parentProjects.contains(it.parent?.name) && filter(it) }.forEach { action(it) }
