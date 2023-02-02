ext { set("publish", false) }

dependencies {
    api(libs.kotlinx.core)
    api(libs.jackson.kotlin)
    implementation(testLibs.testcontainers)
}
