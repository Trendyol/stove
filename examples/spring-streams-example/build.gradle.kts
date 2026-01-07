import com.google.protobuf.gradle.id

plugins {
  alias(libs.plugins.spring.plugin)
  alias(libs.plugins.spring.boot.three)
  alias(libs.plugins.spring.dependencyManagement)
  alias(libs.plugins.protobuf)
  idea
  application
}

dependencies {
  implementation(libs.spring.boot.three)
  implementation(libs.spring.boot.three.autoconfigure)
  annotationProcessor(libs.spring.boot.three.annotationProcessor)
  implementation(libs.spring.boot.three.kafka)
  implementation(libs.jackson.kotlin)
  implementation(libs.kafka)
  implementation(libs.kafka.streams)
  implementation(libs.kotlin.reflect)
  implementation(libs.google.protobuf.kotlin)
  implementation(libs.kafka.streams.protobuf.serde)
}

dependencies {
  testImplementation(project(":test-extensions:stove-extensions-kotest"))
  testImplementation(projects.stove.lib.stoveKafka)
  testImplementation(projects.stove.starters.spring.stoveSpring)
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
    artifact = libs.protoc.get().toString()
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

configurations.matching { it.name == "detekt" }.all {
  resolutionStrategy.eachDependency {
    if (requested.group == "org.jetbrains.kotlin") {
      @Suppress("UnstableApiUsage")
      useVersion(io.gitlab.arturbosch.detekt.getSupportedKotlinVersion())
    }
  }
}
