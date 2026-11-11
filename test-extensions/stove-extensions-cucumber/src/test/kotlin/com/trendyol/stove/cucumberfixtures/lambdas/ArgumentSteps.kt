package com.trendyol.stove.cucumberfixtures.lambdas

import com.trendyol.stove.extensions.cucumber.StoveSteps
import io.cucumber.datatable.DataTable
import io.kotest.matchers.shouldBe

class ArgumentSteps : StoveSteps({
  Given<Int, Int, Int, Int>("arguments {int}, {int}, {int}, {int}") { a1, a2, a3, a4 ->
    listOf(a1, a2, a3, a4) shouldBe (1..4).toList()
  }
  Given<Int, Int, Int, Int, Int>("arguments {int}, {int}, {int}, {int}, {int}") { a1, a2, a3, a4, a5 ->
    listOf(a1, a2, a3, a4, a5) shouldBe (1..5).toList()
  }
  Given<Int, Int, Int, Int, Int, Int>("arguments {int}, {int}, {int}, {int}, {int}, {int}") { a1, a2, a3, a4, a5, a6 ->
    listOf(a1, a2, a3, a4, a5, a6) shouldBe (1..6).toList()
  }
  Given<Int, Int, Int, Int, Int, Int, Int>("arguments {int}, {int}, {int}, {int}, {int}, {int}, {int}") { a1, a2, a3, a4, a5, a6, a7 ->
    listOf(a1, a2, a3, a4, a5, a6, a7) shouldBe (1..7).toList()
  }
  Given<Int, Int, Int, Int, Int, Int, Int, Int>("arguments {int}, {int}, {int}, {int}, {int}, {int}, {int}, {int}") { a1, a2, a3, a4, a5, a6, a7, a8 ->
    listOf(a1, a2, a3, a4, a5, a6, a7, a8) shouldBe (1..8).toList()
  }
  Given<Int, Int, Int, Int, Int, Int, Int, Int, Int>("arguments {int}, {int}, {int}, {int}, {int}, {int}, {int}, {int}, {int}") { a1, a2, a3, a4, a5, a6, a7, a8, a9 ->
    listOf(a1, a2, a3, a4, a5, a6, a7, a8, a9) shouldBe (1..9).toList()
  }
  Then("a lambda converts a typed table") { table: DataTable ->
    table.asList(Int::class.javaObjectType) shouldBe listOf(10, 20)
  }
})
