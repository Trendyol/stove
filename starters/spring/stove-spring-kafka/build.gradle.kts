dependencies {
    api(projects.lib.stove)
    api(libs.testcontainers.kafka)
    compileOnly(libs.spring.boot.kafka)
    implementation(libs.caffeine)
    implementation(libs.pprint)
}

dependencies {
    testImplementation(libs.kotest.runner.junit5)
}
