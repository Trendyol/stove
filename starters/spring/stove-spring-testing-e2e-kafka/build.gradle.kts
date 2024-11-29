import com.google.protobuf.gradle.id

plugins {
  alias(libs.plugins.protobuf)
}

dependencies {
  api(projects.lib.stoveTestingE2e)
  api(libs.testcontainers.kafka)
  implementation(libs.spring.boot.kafka)
  implementation(libs.caffeine)
  implementation(libs.pprint)
}

dependencies {
  testAnnotationProcessor(libs.spring.boot.annotationProcessor)
  testImplementation(libs.spring.boot.autoconfigure)
  testImplementation(projects.starters.spring.stoveSpringTestingE2e)
  testImplementation(libs.logback.classic)
  testImplementation(libs.google.protobuf.kotlin)
  testImplementation(libs.kafka.streams.protobuf.serde)
}

protobuf {
  protoc {
    artifact = libs.protoc.get().toString()
  }

  generateProtoTasks {
    all().forEach {
      it.descriptorSetOptions.includeSourceInfo = true
      it.descriptorSetOptions.includeImports = true
      it.builtins { id("kotlin") }
    }
  }
}


