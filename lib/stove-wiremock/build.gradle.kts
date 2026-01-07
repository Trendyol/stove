dependencies {
  api(projects.lib.stove)
  api(libs.wiremock.standalone)
  api(libs.caffeine)
}

dependencies {
  testImplementation(project(":test-extensions:stove-extensions-kotest"))
}
