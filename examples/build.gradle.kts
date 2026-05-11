subprojects {
  configurations.all {
    this.resolutionStrategy {
      eachDependency {
        if (requested.group == libs.kotlinx.core.get().group && requested.name.startsWith("kotlinx-coroutines")) {
          useVersion(libs.versions.kotlinx.asProvider().get())
        }
      }
    }
  }
}
