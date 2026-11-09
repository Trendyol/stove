plugins {
  alias(libs.plugins.quarkus)
}

dependencies {
  implementation(enforcedPlatform(libs.quarkus))
  implementation(libs.quarkus.arc)
  testImplementation(projects.starters.quarkus.stoveQuarkus)
}

tasks.test {
  dependsOn("quarkusBuild")
  systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}
