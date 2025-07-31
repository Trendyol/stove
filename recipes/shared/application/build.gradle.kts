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
  testImplementation(libs.arrow.core)
}
