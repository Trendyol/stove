package com.trendyol.stove.extensions.kotest

import io.kotest.core.extensions.TestCaseExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestType
import io.kotest.engine.test.TestResult
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Data captured by the [HierarchyCaptureExtension] for each intercepted test case.
 */
data class CapturedTestInfo(
  val specSimpleName: String?,
  val testParts: List<String>,
  val displayPath: List<String>,
  val leafName: String,
  val prefix: String?,
  val type: TestType,
  val hasParent: Boolean,
  val parentType: TestType?
)

/**
 * A [TestCaseExtension] that captures hierarchy metadata from every intercepted test case
 * into a shared list for later assertion.
 */
class HierarchyCaptureExtension(
  private val captured: MutableList<CapturedTestInfo>
) : TestCaseExtension {
  override suspend fun intercept(
    testCase: TestCase,
    execute: suspend (TestCase) -> TestResult
  ): TestResult {
    // Build display path by traversing parents and prepending prefix to each name
    val displayPath = buildDisplayPath(testCase)
    captured.add(
      CapturedTestInfo(
        specSimpleName = testCase.spec::class.simpleName,
        testParts = testCase.descriptor.testParts(),
        displayPath = displayPath,
        leafName = testCase.name.name,
        prefix = testCase.name.prefix,
        type = testCase.type,
        hasParent = testCase.parent != null,
        parentType = testCase.parent?.type
      )
    )
    return execute(testCase)
  }

  private fun buildDisplayPath(testCase: TestCase): List<String> {
    val chain = mutableListOf<TestCase>()
    var current: TestCase? = testCase
    while (current != null) {
      chain.add(0, current)
      current = current.parent
    }
    return chain.map { tc ->
      val prefix = tc.name.prefix ?: ""
      "$prefix${tc.name.name}"
    }
  }
}

// -- FunSpec flat tests --

private val funSpecFlatCaptures = CopyOnWriteArrayList<CapturedTestInfo>()

class FunSpecFlatHierarchyTest : FunSpec({
  extensions(HierarchyCaptureExtension(funSpecFlatCaptures))

  test("flat test one") {
    val mine = funSpecFlatCaptures.first { it.leafName == "flat test one" }
    mine.testParts shouldContainExactly listOf("flat test one")
    mine.displayPath shouldContainExactly listOf("flat test one")
    mine.type shouldBe TestType.Test
    mine.hasParent shouldBe false
    mine.parentType shouldBe null
    mine.prefix shouldBe null
    mine.specSimpleName shouldBe "FunSpecFlatHierarchyTest"
  }
})

// -- FunSpec with context blocks --

private val funSpecContextCaptures = CopyOnWriteArrayList<CapturedTestInfo>()

class FunSpecContextHierarchyTest : FunSpec({
  extensions(HierarchyCaptureExtension(funSpecContextCaptures))

  context("order creation") {
    test("should create order") {
      val mine = funSpecContextCaptures.first { it.leafName == "should create order" }
      // testParts() uses raw DescriptorId values (no prefixes)
      mine.testParts shouldContainExactly listOf("order creation", "should create order")
      // displayPath includes prefix+name for each level (FunSpec context has "context " prefix)
      mine.displayPath shouldContainExactly listOf("context order creation", "should create order")
      mine.type shouldBe TestType.Test
      mine.hasParent shouldBe true
      mine.parentType shouldBe TestType.Container
    }

    test("should validate order") {
      val mine = funSpecContextCaptures.first { it.leafName == "should validate order" }
      mine.testParts shouldContainExactly listOf("order creation", "should validate order")
    }
  }

  test("top level test") {
    val mine = funSpecContextCaptures.first { it.leafName == "top level test" }
    mine.testParts shouldContainExactly listOf("top level test")
    mine.hasParent shouldBe false
  }

  // This test runs last and verifies container interception
  test("intercept is called for container test cases") {
    val containers = funSpecContextCaptures.filter { it.type == TestType.Container }
    containers shouldHaveSize 1
    containers.first().leafName shouldBe "order creation"
    containers.first().testParts shouldContainExactly listOf("order creation")
  }
})

// -- BehaviourSpec --

private val behaviourSpecCaptures = CopyOnWriteArrayList<CapturedTestInfo>()

class BehaviourSpecHierarchyTest : BehaviorSpec({
  extensions(HierarchyCaptureExtension(behaviourSpecCaptures))

  given("a valid order request") {
    `when`("creating an order") {
      then("should succeed") {
        val mine = behaviourSpecCaptures.first { it.leafName == "should succeed" }
        // testParts() returns raw names without prefixes
        mine.testParts shouldContainExactly listOf(
          "a valid order request",
          "creating an order",
          "should succeed"
        )
        // displayPath includes the style-specific prefixes
        mine.displayPath shouldContainExactly listOf(
          "Given: a valid order request",
          "When: creating an order",
          "Then: should succeed"
        )
        mine.type shouldBe TestType.Test
        mine.hasParent shouldBe true
        mine.parentType shouldBe TestType.Container
        mine.prefix shouldBe "Then: "
      }

      then("should publish event") {
        val mine = behaviourSpecCaptures.first { it.leafName == "should publish event" }
        mine.displayPath shouldContainExactly listOf(
          "Given: a valid order request",
          "When: creating an order",
          "Then: should publish event"
        )
      }
    }
  }

  // Verify containers were intercepted and have correct prefixes
  given("container interception check") {
    then("given and when containers are intercepted") {
      val containers = behaviourSpecCaptures.filter { it.type == TestType.Container }
      // given("a valid order request") + when("creating an order") + given("container interception check")
      containers.size shouldBe 3
      val givenContainer = containers.first { it.leafName == "a valid order request" }
      givenContainer.prefix shouldBe "Given: "
      givenContainer.displayPath shouldContainExactly listOf("Given: a valid order request")
      val whenContainer = containers.first { it.leafName == "creating an order" }
      whenContainer.prefix shouldBe "When: "
    }
  }
})

// -- StringSpec --

private val stringSpecCaptures = CopyOnWriteArrayList<CapturedTestInfo>()

class StringSpecHierarchyTest : StringSpec({
  extensions(HierarchyCaptureExtension(stringSpecCaptures))

  "should work as a flat test" {
    val mine = stringSpecCaptures.first { it.leafName == "should work as a flat test" }
    mine.testParts shouldContainExactly listOf("should work as a flat test")
    mine.displayPath shouldContainExactly listOf("should work as a flat test")
    mine.type shouldBe TestType.Test
    mine.hasParent shouldBe false
    mine.parentType shouldBe null
    mine.prefix shouldBe null
    mine.specSimpleName shouldBe "StringSpecHierarchyTest"
  }

  "another flat test" {
    val mine = stringSpecCaptures.first { it.leafName == "another flat test" }
    mine.testParts shouldContainExactly listOf("another flat test")
    mine.displayPath shouldContainExactly listOf("another flat test")
    mine.hasParent shouldBe false
  }
})

// -- DescribeSpec --

private val describeSpecCaptures = CopyOnWriteArrayList<CapturedTestInfo>()

class DescribeSpecHierarchyTest : DescribeSpec({
  extensions(HierarchyCaptureExtension(describeSpecCaptures))

  describe("OrderService") {
    it("should create order") {
      val mine = describeSpecCaptures.first { it.leafName == "should create order" }
      // testParts() returns raw names without prefixes
      mine.testParts shouldContainExactly listOf("OrderService", "should create order")
      // displayPath includes "Describe: " prefix for describe blocks
      mine.displayPath shouldContainExactly listOf("Describe: OrderService", "should create order")
      mine.type shouldBe TestType.Test
      mine.hasParent shouldBe true
      mine.parentType shouldBe TestType.Container
    }

    describe("with invalid input") {
      it("should fail validation") {
        val mine = describeSpecCaptures.first { it.leafName == "should fail validation" }
        mine.testParts shouldContainExactly listOf(
          "OrderService",
          "with invalid input",
          "should fail validation"
        )
        mine.displayPath shouldContainExactly listOf(
          "Describe: OrderService",
          "Describe: with invalid input",
          "should fail validation"
        )
      }
    }
  }
})
