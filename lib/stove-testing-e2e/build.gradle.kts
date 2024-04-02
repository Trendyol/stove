dependencies {
    api(libs.kotlinx.core)
    api(libs.jackson.kotlin)
    api(libs.testcontainers) {
        version {
            require(libs.testcontainers.asProvider().get().version!!)
        }
    }
}
