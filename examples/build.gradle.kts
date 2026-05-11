subprojects {
  configurations.configureEach {
    this.resolutionStrategy {
      eachDependency {
        if (requested.group == "com.google.protobuf" && requested.name.startsWith("protobuf-")) {
          useVersion(libs.versions.google.protobuf.get())
          because("Align protobuf runtime with generated code version")
        }
      }
    }
  }
}
