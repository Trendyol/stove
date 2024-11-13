import com.google.protobuf.gradle.*

plugins {
  alias(libs.plugins.spring.plugin)
//  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.dependencyManagement)
  alias(libs.plugins.protobuf)
  idea
  application
  id("org.springframework.boot") version "3.3.4" //todo place in libs
}

apply {
  plugin("com.google.protobuf")
}

dependencies {
  implementation(libs.spring.boot.get3x())
  implementation(libs.spring.boot.get3x().autoconfigure)
  annotationProcessor(libs.spring.boot.get3x().annotationProcessor)
  implementation(libs.spring.boot.get3x().kafka)
  implementation(libs.jackson.kotlin)
  implementation(libs.kafka.streams)
  implementation(libs.kotlin.reflect)
  implementation(libs.google.protobuf.kotlin.get3x())
  implementation(libs.google.protobuf.java.get3x())
  implementation("org.apache.kafka:kafka-streams:3.7.1")
  implementation(libs.kafka.streams.registry)
}

dependencies {
  testImplementation(projects.stove.lib.stoveTestingE2eKafka)
  testImplementation(projects.stove.starters.spring.stoveSpringTestingE2e)
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
  testImplementation(libs.testcontainers.kafka)

}

application { mainClass.set("stove.spring.streams.example.ExampleAppkt") }

java.sourceSets["main"].java {
  srcDir("build/generated/source/proto/main/java")
  srcDir("build/generated/source/proto/main/kotlin")
}

tasks.withType<Test> {
  useJUnitPlatform()
}

protobuf {
  protoc {
    artifact = "com.google.protobuf:protoc:3.25.5"
  }

  generateProtoTasks {
    all().forEach {
      // If true, the descriptor set will contain line number information
      // and comments. Default is false.
      it.descriptorSetOptions.includeSourceInfo = true

      // If true, the descriptor set will contain all transitive imports and
      // is therefore self-contained. Default is false.
      it.descriptorSetOptions.includeImports = true
      it.builtins {
        id("kotlin")
      }
    }
  }
}
