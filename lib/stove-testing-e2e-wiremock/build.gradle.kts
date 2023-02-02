ext { set("publish", true) }

dependencies {
    api(project(":lib:stove-testing-e2e"))
    api(testLibs.wiremock)
}
