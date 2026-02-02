package com.trendyol.stove.containers

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StoveContainerTest :
  FunSpec({

    context("ExecResult") {
      test("should store exit code, stdout, and stderr") {
        val result = ExecResult(
          exitCode = 0,
          stdout = "command output",
          stderr = ""
        )

        result.exitCode shouldBe 0
        result.stdout shouldBe "command output"
        result.stderr shouldBe ""
      }

      test("should handle non-zero exit code") {
        val result = ExecResult(
          exitCode = 1,
          stdout = "",
          stderr = "Error: command not found"
        )

        result.exitCode shouldBe 1
        result.stderr shouldBe "Error: command not found"
      }

      test("should handle timeout with negative exit code") {
        val result = ExecResult(
          exitCode = -1,
          stdout = "partial output",
          stderr = "Command timed out after 60 seconds"
        )

        result.exitCode shouldBe -1
      }

      test("should handle multiline output") {
        val result = ExecResult(
          exitCode = 0,
          stdout = """
            |line 1
            |line 2
            |line 3
          """.trimMargin(),
          stderr = ""
        )

        result.stdout.lines().size shouldBe 3
      }
    }

    context("StoveContainerInspectInformation") {
      test("should store all container information") {
        val info = StoveContainerInspectInformation(
          id = "abc123def456",
          labels = mapOf("app" to "test", "version" to "1.0"),
          name = "/test-container",
          state = "running",
          running = true,
          paused = false,
          restarting = false,
          startedAt = "2024-01-15T10:30:00Z",
          finishedAt = "0001-01-01T00:00:00Z",
          exitCode = 0,
          error = ""
        )

        info.id shouldBe "abc123def456"
        info.labels shouldBe mapOf("app" to "test", "version" to "1.0")
        info.name shouldBe "/test-container"
        info.state shouldBe "running"
        info.running shouldBe true
        info.paused shouldBe false
        info.restarting shouldBe false
        info.exitCode shouldBe 0
        info.error shouldBe ""
      }

      test("should represent paused container") {
        val info = StoveContainerInspectInformation(
          id = "container-id",
          labels = emptyMap(),
          name = "/paused-container",
          state = "paused",
          running = true,
          paused = true,
          restarting = false,
          startedAt = "2024-01-15T10:30:00Z",
          finishedAt = "0001-01-01T00:00:00Z",
          exitCode = 0,
          error = ""
        )

        info.running shouldBe true
        info.paused shouldBe true
      }

      test("should represent stopped container with error") {
        val info = StoveContainerInspectInformation(
          id = "failed-container",
          labels = emptyMap(),
          name = "/failed-container",
          state = "exited",
          running = false,
          paused = false,
          restarting = false,
          startedAt = "2024-01-15T10:30:00Z",
          finishedAt = "2024-01-15T10:35:00Z",
          exitCode = 137,
          error = "OOM killed"
        )

        info.running shouldBe false
        info.exitCode shouldBe 137
        info.error shouldBe "OOM killed"
      }

      test("should represent restarting container") {
        val info = StoveContainerInspectInformation(
          id = "restarting-container",
          labels = emptyMap(),
          name = "/restarting",
          state = "restarting",
          running = false,
          paused = false,
          restarting = true,
          startedAt = "2024-01-15T10:30:00Z",
          finishedAt = "2024-01-15T10:35:00Z",
          exitCode = 1,
          error = ""
        )

        info.restarting shouldBe true
        info.running shouldBe false
      }

      test("should handle empty labels") {
        val info = StoveContainerInspectInformation(
          id = "id",
          labels = emptyMap(),
          name = "/container",
          state = "running",
          running = true,
          paused = false,
          restarting = false,
          startedAt = "",
          finishedAt = "",
          exitCode = 0,
          error = ""
        )

        info.labels shouldBe emptyMap()
      }
    }
  })
