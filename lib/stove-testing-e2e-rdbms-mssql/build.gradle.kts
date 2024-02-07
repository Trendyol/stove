dependencies {
    api(projects.lib.stoveTestingE2eRdbms)
    implementation(libs.testcontainers.mssql)
    implementation(libs.r2dbc.mssql)
    implementation(libs.microsoft.sqlserver.jdbc)
}
