plugins {
  application
  alias(libs.plugins.kotlinx.serialization)
  id("org.graalvm.buildtools.native")
}

application {
  mainClass.set("stove.ktor.bff.ApplicationKt")
}

val indexSessionMigrations by tasks.registering {
  val migrationRoot = layout.projectDirectory.dir("src/main/resources/db/bff").asFile
  val migrationFiles = fileTree(migrationRoot) { include("**/*.sql", "**/*.sql.conf") }
  val migrationResources = layout.buildDirectory.dir("generated/session-migrations")
  description = "Indexes Flyway resources for identical JVM and GraalVM discovery."
  inputs.files(migrationFiles).withPathSensitivity(PathSensitivity.RELATIVE)
  outputs.dir(migrationResources)
  doLast {
    val index = migrationResources.get().file("db/bff/migrations.list").asFile
    index.parentFile.mkdirs()
    val paths = migrationFiles.files.map { "db/bff/${it.relativeTo(migrationRoot).invariantSeparatorsPath}" }.sorted()
    check(paths.isNotEmpty()) { "No BFF migrations found" }
    index.writeText(paths.joinToString("\n", postfix = "\n"))
  }
}
tasks.processResources { from(indexSessionMigrations) }

dependencies {
  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.statuspages)
  implementation(libs.ktor.server.auth)
  implementation(libs.ktor.server.sessions)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.websockets)
  implementation(libs.ktor.server.websockets)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.nimbus.jose)
  implementation(libs.hikari)
  implementation(libs.postgresql)
  implementation(libs.exposed.core)
  implementation(libs.exposed.jdbc)
  implementation(libs.exposed.java.time)
  implementation(libs.exposed.json)
  implementation(libs.flyway.core)
  implementation(libs.flyway.database.postgresql)
  runtimeOnly(libs.slf4j.simple)

  testImplementation(libs.ktor.server.test.host)
  testImplementation(libs.ktor.client.mock)
  testImplementation(projects.lib.stoveOidc)
  testImplementation(projects.lib.stovePostgres)
  testImplementation(projects.lib.stoveHttp)
  testImplementation(projects.starters.ktor.stoveKtor)
  testImplementation(projects.starters.process.stoveProcess)
  testImplementation(projects.testExtensions.stoveExtensionsKotest)
}

graalvmNative {
  toolchainDetection.set(false)
  testSupport.set(false)
  metadataRepository { enabled.set(true) }
  binaries.named("main") {
    imageName.set("stove-oidc-bff")
    buildArgs.addAll("--no-fallback", "--enable-url-protocols=https", "-O1")
    resources.autodetect()
  }
}

val nativeExecutable = tasks.named<org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask>("nativeCompile")
  .flatMap { it.outputFile }

tasks.register<Test>("nativeE2eTest") {
  description = "Runs the same Stove browser scenarios against the native executable."
  group = "verification"
  dependsOn("nativeCompile", "testClasses")
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  inputs.file(nativeExecutable).withPropertyName("nativeExecutable")
  useJUnitPlatform()
  filter {
    includeTestsMatching("stove.ktor.bff.tests.BffTest")
    includeTestsMatching("stove.ktor.bff.tests.GatewayTest")
  }
  systemProperty("bff.native.executable", nativeExecutable.get().asFile.absolutePath)
}

tasks.test {
  filter {
    excludeTestsMatching("stove.ktor.bff.tests.KeycloakBffTest")
    excludeTestsMatching("stove.ktor.bff.tests.Postgres*")
  }
}

for (native in listOf(false, true)) {
  tasks.register<Test>(if (native) "postgresNativeE2eTest" else "postgresE2eTest") {
    description = "Verifies PostgreSQL sessions on ${if (native) "native with Keycloak/DPoP" else "JVM with NAV"}. Requires Docker."
    group = "verification"
    dependsOn("testClasses")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
      includeTestsMatching("stove.ktor.bff.tests.Postgres*")
      includeTestsMatching(if (native) "stove.ktor.bff.tests.KeycloakBffTest" else "stove.ktor.bff.tests.BffTest")
      includeTestsMatching("stove.ktor.bff.tests.GatewayTest")
    }
    systemProperty("bff.postgres", "true")
    if (native) {
      dependsOn("nativeCompile")
      inputs.file(nativeExecutable).withPropertyName("nativeExecutable")
      systemProperty("bff.native.executable", nativeExecutable.get().asFile.absolutePath)
      systemProperty("bff.keycloak", "true")
    }
  }
}

for (native in listOf(false, true)) {
  tasks.register<Test>(if (native) "keycloakNativeE2eTest" else "keycloakE2eTest") {
    description = "Verifies DPoP and refresh rotation against Keycloak on ${if (native) "native" else "JVM"}. Requires Docker."
    group = "verification"
    dependsOn("testClasses")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
      includeTestsMatching("stove.ktor.bff.tests.KeycloakBffTest")
      includeTestsMatching("stove.ktor.bff.tests.GatewayTest")
    }
    systemProperty("bff.keycloak", "true")
    if (native) {
      dependsOn("nativeCompile")
      inputs.file(nativeExecutable).withPropertyName("nativeExecutable")
      systemProperty("bff.native.executable", nativeExecutable.get().asFile.absolutePath)
    }
  }
}
