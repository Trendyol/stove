dependencies {
    api(projects.lib.stoveTestingE2e)
    compileOnly(libs.spring.boot)
}

dependencies {
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.spring.boot)
}
