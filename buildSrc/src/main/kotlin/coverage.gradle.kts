plugins {
    base
    `jacoco-report-aggregation`
    `test-report-aggregation`
}

dependencies {
    jacocoAggregation(project(":lib:stove-testing-e2e"))
    jacocoAggregation(project(":lib:stove-testing-e2e-couchbase"))
    jacocoAggregation(project(":lib:stove-testing-e2e-http"))
    jacocoAggregation(project(":lib:stove-testing-e2e-kafka"))
    jacocoAggregation(project(":lib:stove-testing-e2e-wiremock"))
    jacocoAggregation(project(":lib:stove-testing-e2e-rdbms"))
    jacocoAggregation(project(":lib:stove-testing-e2e-rdbms-postgres"))
    jacocoAggregation(project(":starters:spring:stove-spring-testing-e2e"))
    jacocoAggregation(project(":starters:spring:stove-spring-testing-e2e-kafka"))
    jacocoAggregation(project(":starters:ktor:stove-ktor-testing-e2e"))
}
