plugins {
  alias(libs.plugins.spring.plugin)
  alias(libs.plugins.protobuf)
  id("com.trendyol.stove.tracing") version libs.versions.stove.get()
}

dependencies {
  // Spring Boot
  implementation(libs.spring.boot.webflux)
  implementation(libs.spring.boot.autoconfigure)
  implementation(libs.spring.boot.data.r2dbc)
  implementation(libs.spring.boot.kafka)

  // Database
  implementation(libs.postgresql.r2dbc)
  implementation(libs.r2dbc.pool)
  implementation(libs.spring.boot.starter.jdbc) // Required for db-scheduler
  implementation(libs.postgresql) // JDBC driver for db-scheduler

  // Scheduling
  implementation(libs.db.scheduler.spring.boot.starter)

  // Kotlin Coroutines
  implementation(libs.kotlinx.core)
  implementation(libs.kotlinx.reactive)
  implementation(libs.kotlinx.reactor)
  implementation(libs.kotlinx.jdk8)

  // Logging
  implementation(libs.kotlin.logging.jvm)

  // OpenTelemetry - for production-grade tracing
  implementation(libs.opentelemetry.extension.kotlin)
  implementation(libs.opentelemetry.instrumentation.annotations)

  // gRPC
  implementation(libs.grpc.protobuf)
  implementation(libs.grpc.stub)
  implementation(libs.grpc.netty)
  implementation(libs.grpc.kotlin.stub)
  implementation(libs.protobuf.kotlin)

  annotationProcessor(libs.spring.boot.annotationProcessor)
}

dependencies {
  // Stove Testing
  testImplementation(stoveLibs.stove)
  testImplementation(stoveLibs.stovePostgres)
  testImplementation(stoveLibs.stoveHttp)
  testImplementation(stoveLibs.stoveWiremock)
  testImplementation(stoveLibs.stoveKafka)
  testImplementation(stoveLibs.stoveSpring)
  testImplementation(stoveLibs.stoveTracing)
  testImplementation(stoveLibs.stoveGrpc)      // For testing our gRPC server
  testImplementation(stoveLibs.stoveGrpcMock)  // For mocking external gRPC services
  testImplementation(stoveLibs.stoveExtensionsKotest)

  // Ktor client for streaming tests
  testImplementation(libs.ktor.client.websockets)
  testImplementation(libs.ktor.client.okhttp)
  testImplementation(libs.ktor.client.content.negotiation)
  testImplementation(libs.ktor.serialization.jackson.json)

  // Testcontainers
  testImplementation(libs.testcontainers.kafka)
}

// ============================================================================
// PROTOBUF CONFIGURATION - gRPC Code Generation
// ============================================================================
protobuf {
  protoc {
    artifact = libs.protoc.get().toString()
  }
  plugins {
    create("grpc") {
      artifact = libs.grpc.protoc.gen.java.get().toString()
    }
    create("grpckt") {
      artifact = "${libs.grpc.protoc.gen.kotlin.get()}:jdk8@jar"
    }
  }
  generateProtoTasks {
    all().forEach { task ->
      task.plugins {
        create("grpc")
        create("grpckt")
      }
      task.builtins {
        create("kotlin")
      }
    }
  }
}

// ============================================================================
// TRACING SETUP - OpenTelemetry Java Agent
// ============================================================================
stoveTracing {
  serviceName.set("stove-kotlin-spring-showcase")
  testTaskNames.set(listOf("e2eTest"))
  otelAgentVersion.set(libs.opentelemetry.instrumentation.annotations.get().version!!)
}
