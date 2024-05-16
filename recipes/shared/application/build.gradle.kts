plugins {
  kotlin("jvm") version libs.versions.kotlin
  java
  idea
}

dependencies {
  implementation(libs.jackson.databind)
  compileOnly(libs.lombok)
  annotationProcessor(libs.lombok)
}

dependencies {
  testCompileOnly(libs.lombok)
  testAnnotationProcessor(libs.lombok)
  testImplementation(libs.kotest.framework.api)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.arrow.core)
}
