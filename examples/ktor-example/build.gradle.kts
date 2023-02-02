@file:Suppress("UnstableApiUsage", "DSL_SCOPE_VIOLATION")

plugins {
    kotlin("jvm") version 1.8.10
    application
    idea
    kotlin("plugin.serialization") version libs.versions.kotlin
}

application {
    val groupId = rootProject.group.toString()
    val artifactId = project.name
    mainClass.set("$groupId.$artifactId.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

object TestFolders {
    const val Integration = "test-int"
    const val e2e = "test-e2e"
}

sourceSets {
    create(TestFolders.e2e) {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }

    val testE2eImplementation by
    configurations.getting { extendsFrom(configurations.testImplementation.get()) }
    configurations["testE2eRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())
}

idea {
    module {
        testSources.from(project.sourceSets[TestFolders.e2e].kotlin.srcDirs)
        testResources.from(project.sourceSets[TestFolders.e2e].resources.srcDirs)
    }
}

dependencies {
    implementation(libs.ktor.server)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    implementation(libs.kotlinx.reactor)
    implementation(libs.r2dbc.postgresql)
}

dependencies {
    testImplementation(testLibs.ktor.server.tests.jvm)
    testImplementation(testLibs.kotest.property.jvm)
    testImplementation(testLibs.kotest.runner.junit5)
    testImplementation(project(":lib:stove-testing-e2e-http"))
    testImplementation(project(":lib:stove-testing-e2e-wiremock"))
    testImplementation(project(":lib:stove-testing-e2e-rdbms-postgres"))
    testImplementation(project(":starters:ktor:stove-ktor-testing-e2e"))
}

repositories {
    mavenCentral()
}
