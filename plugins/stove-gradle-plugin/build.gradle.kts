@file:Suppress("UnstableApiUsage")

plugins {
  `kotlin-dsl`
  `java-gradle-plugin`
  alias(libs.plugins.maven.publish)
}

apply(from = "../../gradle/stove-plugin.gradle.kts")

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

val contractFixtureRuntime = configurations.create("contractFixtureRuntime") {
  isCanBeConsumed = false
  attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
}
dependencies { add(contractFixtureRuntime.name, project(":lib:stove-wiremock")) }

tasks.test {
  dependsOn(contractFixtureRuntime)
  systemProperty("stove.fixture.classpath", contractFixtureRuntime.asPath)
  systemProperty("stove.fixture.kotlin", libs.versions.kotlin.get())
  useJUnitPlatform()
}

dependencies {
  testImplementation(libs.kotest.runner.junit6)
  testImplementation(libs.kotest.framework.engine)
  testImplementation(libs.kotest.assertions.core)
}
