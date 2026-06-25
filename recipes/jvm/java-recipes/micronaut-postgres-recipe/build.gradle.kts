plugins {
  alias(libs.plugins.micronaut.application)
  id("com.trendyol.stove.tracing") version libs.versions.stove.get()
  java
}

micronaut {
  version(libs.versions.micronaut.platform.get())
  runtime("netty")
  processing {
    incremental(true)
    annotations("com.trendyol.stove.recipes.micronaut.*")
  }
}

application {
  mainClass = "com.trendyol.stove.recipes.micronaut.Application"
}

graalvmNative.toolchainDetection = false

tasks.e2eTest {
  enabled = runningLocally
}

dependencies {
  annotationProcessor(platform(libs.micronaut.platform))
  annotationProcessor(libs.micronaut.inject.java)
  annotationProcessor(libs.micronaut.http.validation)
  annotationProcessor(libs.micronaut.serde.processor)

  implementation(platform(libs.micronaut.platform))
  implementation(libs.micronaut.http.server.netty)
  implementation(libs.micronaut.http.client)
  implementation(libs.micronaut.serde.jackson)
  implementation(libs.micronaut.data.r2dbc)
  implementation(libs.r2dbc.postgresql)
  implementation(libs.postgresql)
  implementation(libs.logback.classic)
  runtimeOnly(libs.snakeyaml)
}

dependencies {
  testImplementation(stoveLibs.stove)
  testImplementation(stoveLibs.stoveMicronaut)
  testImplementation(stoveLibs.stovePostgres)
  testImplementation(stoveLibs.stoveHttp)
  testImplementation(stoveLibs.stoveWiremock)
  testImplementation(stoveLibs.stoveTracing)
  testImplementation(stoveLibs.stoveExtensionsKotest)
}

stoveTracing {
  serviceName.set("micronaut-postgres-recipe")
  testTaskNames.set(listOf("e2eTest"))
  otelAgentVersion.set(libs.opentelemetry.instrumentation.annotations.get().version!!)
}
