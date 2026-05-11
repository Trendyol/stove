subprojects {
  configurations.configureEach {
    this.resolutionStrategy {
      eachDependency {
        if (requested.group == libs.kotlinx.core.get().group && requested.name.startsWith("kotlinx-coroutines")) {
          useVersion(libs.versions.kotlinx.asProvider().get())
        }
        if (requested.group == "com.google.protobuf" && requested.name.startsWith("protobuf-")) {
          useVersion(libs.versions.google.protobuf.get())
          because("Align protobuf runtime with generated code version")
        }
      }
    }
  }
}
