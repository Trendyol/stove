dependencies {
  api(projects.lib.stoveTestingE2eRdbms)
  api(libs.testcontainers.mssql)
  api(libs.microsoft.sqlserver.jdbc)
}

dependencies {
  testImplementation(libs.logback.classic)
}

