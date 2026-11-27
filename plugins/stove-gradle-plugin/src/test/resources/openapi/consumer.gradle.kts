plugins {
  kotlin("jvm") version "@kotlinVersion@"
  id("com.trendyol.stove")
}
repositories { mavenCentral() }
val suite = sourceSets.create("contractTest")
dependencies { add(suite.implementationConfigurationName, files(file("classpath.txt").readText().split(File.pathSeparator))) }
stove {
  wiremock {
    sourceSet.set(suite.name)
    api("supplier") {
      specUrl.set("http://127.0.0.1:@port@/api.yaml")
      schemaMappings.put("Item", "fixture.Item")
    }
    api("inventory") {
      specText.set(file("inventory.yaml").readText())
      schemaMappings.put("Channel", "fixture.Channel")
    }
  }
}
tasks.register<JavaExec>("exercise") {
  dependsOn(suite.classesTaskName)
  classpath = suite.runtimeClasspath
  mainClass.set("fixture.ConsumerKt")
}
