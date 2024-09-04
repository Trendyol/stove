package com.trendyol.stove

import io.kotest.core.spec.style.FunSpec
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class CapturedOutput(
  private val outBuffer: ByteArrayOutputStream,
  private val errBuffer: ByteArrayOutputStream
) {
  val out: String get() = outBuffer.toString()
  val err: String get() = errBuffer.toString()
}

abstract class ConsoleSpec(body: ConsoleSpec.(CapturedOutput) -> Unit = {}) : FunSpec({
  val originalOut = System.out
  val originalErr = System.err
  val outBuffer = ByteArrayOutputStream()
  val errBuffer = ByteArrayOutputStream()
  val capturedOutput = CapturedOutput(outBuffer, errBuffer)

  beforeSpec {
    System.setOut(PrintStream(outBuffer))
    System.setErr(PrintStream(outBuffer))
  }

  afterSpec {
    System.setOut(originalOut)
    System.setOut(originalErr)
  }

  beforeEach {
    outBuffer.reset()
    errBuffer.reset()
  }

  body(this as ConsoleSpec, capturedOutput)
})
