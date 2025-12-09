dependencies {
    api(projects.starters.spring.stoveSpringTestingE2e)
    implementation(libs.spring.boot.three)
}

dependencies {
    testAnnotationProcessor(libs.spring.boot.three.annotationProcessor)
    testImplementation(libs.spring.boot.three.autoconfigure)
    testImplementation(libs.slf4j.simple)
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.testing.e2e.Stove")
}
