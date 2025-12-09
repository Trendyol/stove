dependencies {
    api(projects.starters.spring.stoveSpringTestingE2eCommon)
    implementation(libs.spring.boot.four)
}

dependencies {
    testAnnotationProcessor(libs.spring.boot.four.annotationProcessor)
    testImplementation(libs.spring.boot.four.autoconfigure)
    testImplementation(libs.slf4j.simple)
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.testing.e2e.Stove")
}
