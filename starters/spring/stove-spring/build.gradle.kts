dependencies {
    api(projects.lib.stove)
    // Both Spring versions as compileOnly - users bring the actual version at runtime
    compileOnly(libs.spring.boot)
    compileOnly(libs.spring.boot.four)
}

dependencies {
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.spring.boot)
}
