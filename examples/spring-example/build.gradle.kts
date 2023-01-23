// @Suppress("DSL_SCOPE_VIOLATION") // TODO: Remove once KTIJ-19369 is fixed
plugins {
    // kotlin("plugin.spring")
    // id(libs.plugins.spring.boot.get().pluginId)
    // idea
    // application
}
//
// dependencies {
//     implementation(project(":starters:spring:stove-spring-starter-kafka"))
//     implementation(project(":starters:spring:stove-spring-starter-couchbase"))
//     implementation(libs.spring.boot.autoconfigure)
//     implementation(libs.spring.boot.itself)
//     implementation(libs.spring.boot.webflux)
//     implementation(libs.spring.boot.actuator)
//     implementation(libs.spring.boot.annotationProcessor)
//     implementation(libs.kotlinx.reactor)
//     implementation(libs.kotlinx.core)
//     implementation(libs.kotlinx.reactive)
// }
//
// dependencies {
//     testImplementation(testLibs.kotest.property.jvm)
//     testImplementation(testLibs.kotest.runner.junit5)
//     testImplementation(testLibs.kotest.extensions.spring)
//     testImplementation(project(":lib:stove-testing-e2e"))
//     testImplementation(project(":lib:stove-testing-e2e-http"))
//     testImplementation(project(":lib:stove-testing-e2e-wiremock"))
//     testImplementation(project(":lib:stove-testing-e2e-couchbase"))
//     testImplementation(project(":starters:spring:stove-spring-testing-e2e"))
//     testImplementation(project(":starters:spring:stove-spring-testing-e2e-kafka"))
// }
//
// object TestFolders {
//     const val Integration = "test-int"
//     const val e2e = "test-e2e"
// }
//
// sourceSets {
//     create(TestFolders.e2e) {
//         compileClasspath += sourceSets.main.get().output
//         runtimeClasspath += sourceSets.main.get().output
//     }
//
//     val testE2eImplementation by
//     configurations.getting { extendsFrom(configurations.testImplementation.get()) }
//     configurations["testE2eRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())
// }
//
// idea {
//     module {
//         testSources.from(project.sourceSets[TestFolders.e2e].kotlin.srcDirs)
//         testResources.from(project.sourceSets[TestFolders.e2e].resources.srcDirs)
//     }
// }
//
// application { mainClass.set("com.trendyol.qc.internationalservice.ExampleAppkt") }
