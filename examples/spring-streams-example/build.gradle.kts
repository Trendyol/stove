import com.google.protobuf.gradle.*

plugins {
  alias(libs.plugins.spring.plugin)
  idea
  application
  id("com.google.protobuf") version "0.8.19"
  id("io.spring.dependency-management") version "1.1.6"
  id("org.springframework.boot") version "3.3.4"
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

  implementation("org.apache.kafka:kafka-streams")
  implementation("org.jetbrains.kotlin:kotlin-reflect")
  implementation("com.google.protobuf:protobuf-kotlin:3.25.5")
  implementation("com.google.protobuf:protobuf-java:3.25.5")
  implementation("io.confluent:kafka-streams-protobuf-serde:7.7.1")
}

dependencies {
  testImplementation(projects.stove.lib.stoveTestingE2eKafka)
  testImplementation(projects.stove.starters.spring.stoveSpringTestingE2e)
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation("org.testcontainers:kafka:1.20.3")

}

application { mainClass.set("stove.spring.streams.example.ExampleAppkt") }

java.sourceSets["main"].java {
  srcDir("build/generated/source/proto/main/java")
  srcDir("build/generated/source/proto/main/kotlin")
}


buildscript {
  repositories {
    mavenCentral()
    maven {
      url = uri("https://packages.confluent.io/maven/")
    }
    maven {
      url = uri("https://jitpack.io")
    }
  }
}

repositories {
  mavenCentral()
  maven {
    url = uri("https://packages.confluent.io/maven/")
  }
  maven {
    url = uri("https://plugins.gradle.org/m2/")
  }
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
