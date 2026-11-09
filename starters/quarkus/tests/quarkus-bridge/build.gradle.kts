plugins {
  alias(libs.plugins.quarkus)
}

dependencies {
  implementation(enforcedPlatform(libs.quarkus))
  implementation(libs.quarkus.rest)
  implementation(libs.quarkus.arc)
  testImplementation(projects.starters.quarkus.stoveQuarkus)
  testImplementation(projects.lib.stoveHttp)
}

// This entire module is an e2e fixture. Production applications keep their normal packaging/profile.
quarkus {
  quarkusBuildProperties.put("quarkus.profile", "e2e")
}

val augmentedJar = tasks.named<io.quarkus.gradle.tasks.QuarkusBuild>("quarkusBuild").map { it.runnerJar }
tasks.test {
  dependsOn("quarkusBuild")
  // Load augmented classes (including ArC transformations) instead of raw main output.
  // The uber-jar uses Stove's existing direct-main path, avoiding a second application classloader.
  classpath = files(augmentedJar) + (sourceSets.test.get().runtimeClasspath - sourceSets.main.get().output)
  systemProperty("quarkus.profile", "e2e")
  systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}
