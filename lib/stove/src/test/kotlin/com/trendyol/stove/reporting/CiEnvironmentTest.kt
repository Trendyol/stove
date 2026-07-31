package com.trendyol.stove.reporting

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CiEnvironmentTest :
  FunSpec({
    test("detects common CI environment flags") {
      isRunningOnCI(emptyMap()) shouldBe false
      isRunningOnCI(mapOf("CI" to "false", "GITHUB_ACTIONS" to "0")) shouldBe false
      isRunningOnCI(mapOf("CI" to "true")) shouldBe true
      isRunningOnCI(mapOf("GITHUB_ACTIONS" to "1")) shouldBe true
      isRunningOnCI(mapOf("JENKINS_URL" to "https://jenkins.example")) shouldBe true
    }
  })
