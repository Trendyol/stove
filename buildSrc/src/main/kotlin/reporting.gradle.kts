plugins {
    base
    `jacoco-report-aggregation`
    `test-report-aggregation`
}

dependencies {
    jacocoAggregation(project(project.path))
}

// task to gather code coverage from multiple subprojects
// NOTE: the `JacocoReport` tasks do *not* depend on the `test` task by default. Meaning you have to ensure
// that `test` (or other tasks generating code coverage) run before generating the report.
// You can achieve this by calling the `test` lifecycle task manually
// $ ./gradlew test codeCoverageReport
tasks.register<JacocoReport>(TaskNames.codeCoverageReport) {
    // If a subproject applies the 'jacoco' plugin, add the result it to the report
    subprojects {
        val subproject = this
        subproject.plugins.withType<JacocoPlugin>().configureEach {
            subproject.tasks.matching { it.extensions.findByType<JacocoTaskExtension>() != null }.configureEach {
                val testTask = this
                sourceSets(subproject.sourceSets["main"])
                executionData(testTask)
            }

            // To automatically run `test` every time `./gradlew codeCoverageReport` is called,
            // you may want to set up a task dependency between them as shown below.
            // Note that this requires the `test` tasks to be resolved eagerly (see `forEach`) which
            // may have a negative effect on the configuration time of your build.
            subproject.tasks.matching { it.extensions.findByType<JacocoTaskExtension>() != null }.forEach {
                rootProject.tasks["codeCoverageReport"].dependsOn(it)
            }
        }
    }

    classDirectories.setFrom(
        files(
            classDirectories.map {
                fileTree(it) {
                    exclude("**/examples/**")
                    exclude("**/examples/*")
                }
            }
        )
    )

    // enable the different report types (html, xml, csv)
    reports {
        // xml is usually used to integrate code coverage with
        // other tools like SonarQube, Coveralls or Codecov
        xml.required.set(true)

        // HTML reports can be used to see code coverage
        // without any external tools
        html.required.set(true)
    }
}

evaluationDependsOnChildren()
tasks.register<TestReport>(TaskNames.testAggregateReports) {
    mustRunAfter(tasks.named(TaskNames.codeCoverageReport))
    destinationDirectory.set(rootProject.buildDir.resolve("reports/html"))
    val allTests = subprojects.flatMap { project -> project.tasks.withType<Test>() }
    testResults.from(allTests)

    doLast {
        allTests.forEach { task ->
            copy {
                from(task.reports.junitXml.outputLocation.get().asFile)
                include("*.xml")
                into(rootProject.buildDir.resolve("reports/xml/${task.name}"))
            }
        }
    }
}

tasks.check { dependsOn(tasks.named(TaskNames.codeCoverageReport)) }
tasks.named(TaskNames.codeCoverageReport).configure {
    finalizedBy(tasks.named(TaskNames.testAggregateReports))
}
