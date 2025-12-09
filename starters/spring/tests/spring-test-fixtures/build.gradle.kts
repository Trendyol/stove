import com.google.protobuf.gradle.id

plugins {
  `java-test-fixtures`
  alias(libs.plugins.protobuf)
}

dependencies {
  testFixturesApi(projects.starters.spring.stoveSpringTestingE2e)
  testFixturesApi(projects.starters.spring.stoveSpringTestingE2eKafka)
  testFixturesApi(libs.kotest.runner.junit5)
  testFixturesApi(libs.google.protobuf.kotlin)
  testFixturesApi(libs.kafka.streams.protobuf.serde)
  
  // Spring Boot as compileOnly - version provided by consuming module
  testFixturesCompileOnly(libs.spring.boot)
  testFixturesCompileOnly(libs.spring.boot.autoconfigure)
  testFixturesCompileOnly(libs.spring.boot.kafka)
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
