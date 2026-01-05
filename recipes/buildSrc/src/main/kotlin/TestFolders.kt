object TestFolders {
    const val integration = "test-int"
    const val e2e = "test-e2e"
    const val shared = "test-shared"
}

val runningOnCI get() = System.getenv("CI") == "true"

val runningLocally get() = !runningOnCI
