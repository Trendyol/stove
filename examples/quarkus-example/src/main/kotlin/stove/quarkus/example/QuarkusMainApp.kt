package stove.quarkus.example

import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.annotations.QuarkusMain

@QuarkusMain
object QuarkusMainApp {
  @JvmStatic
  fun main(args: Array<String>) {
    Quarkus.run(*args)
  }
}
