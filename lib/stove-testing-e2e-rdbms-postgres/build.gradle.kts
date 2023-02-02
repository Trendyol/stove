ext { set("publish", true) }

dependencies {
    api(project(":lib:stove-testing-e2e-rdbms"))
    implementation(libs.r2dbc.postgresql)
    implementation(testLibs.testcontainers.postgres)
}
