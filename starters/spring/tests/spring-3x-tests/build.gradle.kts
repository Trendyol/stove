dependencies {
    api(projects.starters.spring.stoveSpring)
    implementation(libs.spring.boot.three)
    
    testImplementation(project(":test-extensions:stove-extensions-kotest"))
    testImplementation(testFixtures(projects.starters.spring.tests.springTestFixtures))
    testImplementation(libs.spring.boot.three.autoconfigure)
    testImplementation(libs.slf4j.simple)
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.Stove")
}
