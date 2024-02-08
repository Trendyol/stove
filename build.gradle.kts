import org.jetbrains.dokka.gradle.DokkaMultiModuleTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm").version(libs.versions.kotlin)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.gitVersioning)
    `jacoco-report-aggregation`
    `test-report-aggregation`
    id(libs.plugins.jacoco.get().pluginId)
    id("stove-publishing") apply false
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

subprojects.of("lib", "spring", "examples", "ktor") {
    apply {
        plugin("kotlin")
        plugin(rootProject.libs.plugins.kotlinter.get().pluginId)
        plugin(rootProject.libs.plugins.dokka.get().pluginId)
        plugin(rootProject.libs.plugins.jacoco.get().pluginId)
        plugin(rootProject.libs.plugins.jacocoReportAggregation.get().pluginId)
        plugin("test-report-aggregation")
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
            configure<JacocoTaskExtension> {
                isIncludeNoLocationClasses = false
            }
            dependsOn(formatKotlin)
            useJUnitPlatform()
            ignoreFailures = true
            testlogger {
                setTheme("mocha")
                showStandardStreams = true
            }
            reports {
                junitXml.required.set(true)
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

val related = subprojects.of("lib", "spring", "examples", "ktor")
tasks.create<JacocoReport>("jacocoRootReport") {
    group = "Reporting"
    related.forEach { dependsOn(it.tasks.test) }
    related.forEach {
        sourceSets(it.sourceSets.getByName("main"))
        executionData.from(it.layout.buildDirectory.file("jacoco/test.exec"))
    }
    classDirectories.setFrom(
        files(classDirectories.map {
            fileTree(it) {
                exclude("**/contracts/**")
                exclude("**/generated/**")
                exclude("**/examples/**")
                exclude("**/example/**")
                exclude("**/standalone/**")
                exclude("**/functional/**")
                exclude("**/abstractions/**")
                exclude("**/serialization/**")
            }
        })
    )
    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
    }
}

tasks.register<Copy>("testAggregateXmlReports") {
    group = "Reporting"
    related.forEach {
        dependsOn(it.tasks.testAggregateTestReport)
        mustRunAfter(it.tasks.testAggregateTestReport)
    }
    val testResults = related.map { it.tasks.test.get().outputs.files }
    duplicatesStrategy = DuplicatesStrategy.WARN
    from(testResults)
    include("*.xml")
    into(rootProject.layout.buildDirectory.dir("reports/xml"))
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

subprojects.of("lib", "spring", "ktor", filter = { p -> publishedProjects.contains(p.name) }) {
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
