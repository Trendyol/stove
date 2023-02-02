dependencies {
    api(project(":lib:stove-testing-e2e"))
    implementation(libs.ktor.server.host.common)
    implementation(libs.koin.ktor)
}
