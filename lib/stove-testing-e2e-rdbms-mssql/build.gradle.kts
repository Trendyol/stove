dependencies {
  api(projects.lib.stoveTestingE2eRdbms)
  api(libs.testcontainers.mssql)
  api(libs.microsoft.sqlserver.jdbc)
}

tasks.test.configure {
  systemProperty("kotest.framework.config.fqn", "com.trendyol.stove.testing.e2e.rdbms.mssql.Stove")
}
