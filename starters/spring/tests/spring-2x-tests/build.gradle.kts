dependencies {
    api(projects.starters.spring.stoveSpringTestingE2e)
    implementation(libs.spring.boot)
    
    testImplementation(testFixtures(projects.starters.spring.tests.springTestFixtures))
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.slf4j.simple)
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.testing.e2e.Stove")
}
