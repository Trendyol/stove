dependencies {
    api(projects.lib.stoveTestingE2eRdbms)
    api(libs.r2dbc.mssql)
    api(libs.testcontainers.mssql)
    api(libs.microsoft.sqlserver.jdbc)
}
