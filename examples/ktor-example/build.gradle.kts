val ktor_version: String = "2.2.2"
val koin_version: String = "3.3.0"

plugins {
    kotlin("jvm") version "1.8.0"
    // id("io.ktor.plugin") version "2.2.2"
    application
    idea
    kotlin("plugin.serialization") version "1.8.0"
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
    implementation("io.ktor:ktor-server:$ktor_version")
    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
    implementation("io.ktor:ktor-server-call-logging:$ktor_version")
    implementation("io.insert-koin:koin-ktor:$koin_version")
    implementation("io.insert-koin:koin-logger-slf4j:$koin_version")
    implementation(libs.kotlinx.reactor)
    implementation(libs.r2dbc.postgresql)
}

dependencies {
    testImplementation("io.ktor:ktor-server-tests-jvm:$ktor_version")
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
