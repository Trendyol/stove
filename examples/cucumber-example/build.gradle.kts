plugins {
  id("com.trendyol.stove.tracing")
}

val dashboardEnabled = providers.gradleProperty("dashboard").isPresent

stoveTracing {
  enabled.set(dashboardEnabled)
  serviceName.set("cucumber-example")
  otelAgentVersion.set(libs.versions.opentelemetry.instrumentation.get())
  profiling {
    enabled.set(dashboardEnabled)
    agentVersion.set(libs.versions.pyroscope.get())
    allocations.set(true)
    liveAllocations.set(true)
    lockThreshold.set("10ms")
  }
}

dependencies {
  implementation(libs.ktor.server)
  implementation(libs.ktor.server.cio)

  testImplementation(projects.testExtensions.stoveExtensionsCucumber)
  testImplementation(projects.starters.ktor.stoveKtor)
  testImplementation(projects.lib.stoveHttp)
  testImplementation(projects.lib.stoveDashboard)
  testImplementation(projects.lib.stoveTracing)
  testImplementation(libs.cucumber.engine)
  testImplementation(libs.cucumber.picocontainer)
  testImplementation(libs.junit.platform.suite)
  testImplementation(libs.logback.classic)
  testRuntimeOnly(libs.jackson.jdk8)
}

// Only the suite engine discovers the suite; its selector delegates features to Cucumber.
// Keeping the Cucumber engine out of top-level discovery prevents duplicate execution.
tasks.test {
  useJUnitPlatform { includeEngines("junit-platform-suite") }
  // Exercise the hint workaround even on fast machines if it is accidentally removed.
  systemProperty("cucumber.glue.hint.threshold", "PT0S")
  systemProperty("cucumber.filter.tags", providers.gradleProperty("cucumber.tags").getOrElse("not @failure-demo"))
  systemProperty("stove.dashboard.enabled", dashboardEnabled)
}
