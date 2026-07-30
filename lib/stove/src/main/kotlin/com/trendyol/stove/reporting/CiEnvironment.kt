package com.trendyol.stove.reporting

private val CI_ENVIRONMENT_VARIABLES = setOf(
  "CI",
  "GITHUB_ACTIONS",
  "GITLAB_CI",
  "JENKINS_URL",
  "TEAMCITY_VERSION",
  "TF_BUILD",
  "BUILDKITE",
  "CIRCLECI",
  "BITBUCKET_BUILD_NUMBER"
)

internal fun isRunningOnCI(environment: Map<String, String> = System.getenv()): Boolean =
  CI_ENVIRONMENT_VARIABLES.any { variable ->
    val value = environment[variable]?.trim()
    !value.isNullOrEmpty() && value != "0" && !value.equals("false", ignoreCase = true)
  }
