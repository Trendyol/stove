plugins {
}

dependencies {
    api(project(":lib:stove-testing-e2e"))
    implementation(libs.kotlinx.core)
    implementation(libs.kotlinx.io.reactor)
    implementation(libs.kotlinx.reactive)
    implementation(libs.kotlinx.jdk8)
    implementation(libs.kotlinx.jvm)
}
