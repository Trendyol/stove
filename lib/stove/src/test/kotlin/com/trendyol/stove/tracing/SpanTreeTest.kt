package com.trendyol.stove.tracing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class SpanTreeTest :
  FunSpec({

    context("SpanNode") {
      test("hasFailedDescendants should return true when span itself is failed") {
        val node = SpanNode(createSpan(status = SpanStatus.ERROR))

        node.hasFailedDescendants shouldBe true
      }

      test("hasFailedDescendants should return true when child is failed") {
        val child = SpanNode(createSpan(spanId = "child", status = SpanStatus.ERROR))
        val parent = SpanNode(createSpan(spanId = "parent", status = SpanStatus.OK), listOf(child))

        parent.hasFailedDescendants shouldBe true
      }

      test("hasFailedDescendants should return false when all spans are OK") {
        val child = SpanNode(createSpan(spanId = "child", status = SpanStatus.OK))
        val parent = SpanNode(createSpan(spanId = "parent", status = SpanStatus.OK), listOf(child))

        parent.hasFailedDescendants shouldBe false
      }

      test("depth should be 1 for leaf node") {
        val node = SpanNode(createSpan())

        node.depth shouldBe 1
      }

      test("depth should count nested levels") {
        val grandchild = SpanNode(createSpan(spanId = "grandchild"))
        val child = SpanNode(createSpan(spanId = "child"), listOf(grandchild))
        val parent = SpanNode(createSpan(spanId = "parent"), listOf(child))

        parent.depth shouldBe 3
      }

      test("spanCount should count all spans in tree") {
        val grandchild = SpanNode(createSpan(spanId = "grandchild"))
        val child1 = SpanNode(createSpan(spanId = "child1"), listOf(grandchild))
        val child2 = SpanNode(createSpan(spanId = "child2"))
        val parent = SpanNode(createSpan(spanId = "parent"), listOf(child1, child2))

        parent.spanCount shouldBe 4
      }

      test("findFailurePoint should return failed leaf node") {
        val failedChild = SpanNode(createSpan(spanId = "failed", status = SpanStatus.ERROR))
        val parent = SpanNode(createSpan(spanId = "parent", status = SpanStatus.OK), listOf(failedChild))

        val failurePoint = parent.findFailurePoint()

        failurePoint.shouldNotBeNull()
        failurePoint.span.spanId shouldBe "failed"
      }

      test("findFailurePoint should return deepest failure in chain") {
        val deepFailed = SpanNode(createSpan(spanId = "deep", status = SpanStatus.ERROR))
        val middleFailed = SpanNode(createSpan(spanId = "middle", status = SpanStatus.ERROR), listOf(deepFailed))
        val parent = SpanNode(createSpan(spanId = "parent", status = SpanStatus.OK), listOf(middleFailed))

        val failurePoint = parent.findFailurePoint()

        failurePoint.shouldNotBeNull()
        failurePoint.span.spanId shouldBe "deep"
      }

      test("findFailurePoint should return null when no failures") {
        val node = SpanNode(createSpan(status = SpanStatus.OK))

        node.findFailurePoint().shouldBeNull()
      }

      test("flatten should return all spans in tree") {
        val grandchild = SpanNode(createSpan(spanId = "grandchild"))
        val child = SpanNode(createSpan(spanId = "child"), listOf(grandchild))
        val parent = SpanNode(createSpan(spanId = "parent"), listOf(child))

        val flattened = parent.flatten()

        flattened shouldHaveSize 3
        flattened.map { it.spanId } shouldContainExactly listOf("parent", "child", "grandchild")
      }
    }

    context("SpanTree.build") {
      test("should return null for empty list") {
        val result = SpanTree.build(emptyList())

        result.shouldBeNull()
      }

      test("should build single node tree") {
        val span = createSpan(spanId = "root", parentSpanId = null)

        val result = SpanTree.build(listOf(span))

        result.shouldNotBeNull()
        result.span.spanId shouldBe "root"
        result.children shouldHaveSize 0
      }

      test("should build parent-child relationship") {
        val parent = createSpan(spanId = "parent", parentSpanId = null, startTimeNanos = 0)
        val child = createSpan(spanId = "child", parentSpanId = "parent", startTimeNanos = 100)

        val result = SpanTree.build(listOf(parent, child))

        result.shouldNotBeNull()
        result.span.spanId shouldBe "parent"
        result.children shouldHaveSize 1
        result.children[0].span.spanId shouldBe "child"
      }

      test("should order children by start time") {
        val parent = createSpan(spanId = "parent", parentSpanId = null, startTimeNanos = 0)
        val child1 = createSpan(spanId = "child1", parentSpanId = "parent", startTimeNanos = 200)
        val child2 = createSpan(spanId = "child2", parentSpanId = "parent", startTimeNanos = 100)

        val result = SpanTree.build(listOf(parent, child1, child2))

        result.shouldNotBeNull()
        result.children shouldHaveSize 2
        result.children[0].span.spanId shouldBe "child2"
        result.children[1].span.spanId shouldBe "child1"
      }

      test("should handle orphaned spans as roots") {
        val orphan = createSpan(spanId = "orphan", parentSpanId = "nonexistent")

        val result = SpanTree.build(listOf(orphan))

        result.shouldNotBeNull()
        result.span.spanId shouldBe "orphan"
      }

      test("should create virtual root for multiple roots") {
        val root1 = createSpan(spanId = "root1", parentSpanId = null, startTimeNanos = 0)
        val root2 = createSpan(spanId = "root2", parentSpanId = null, startTimeNanos = 100)

        val result = SpanTree.build(listOf(root1, root2))

        result.shouldNotBeNull()
        result.span.operationName shouldBe "trace-root"
        result.children shouldHaveSize 2
      }

      test("should build deep tree structure") {
        val root = createSpan(spanId = "root", parentSpanId = null, startTimeNanos = 0)
        val child = createSpan(spanId = "child", parentSpanId = "root", startTimeNanos = 100)
        val grandchild = createSpan(spanId = "grandchild", parentSpanId = "child", startTimeNanos = 200)

        val result = SpanTree.build(listOf(root, child, grandchild))

        result.shouldNotBeNull()
        result.depth shouldBe 3
        result.spanCount shouldBe 3
      }
    }

    context("SpanTree.findSpan") {
      test("should find span matching predicate") {
        val root = createSpan(spanId = "root", operationName = "root-op")
        val child = createSpan(spanId = "child", operationName = "child-op", parentSpanId = "root")
        val tree = SpanTree.build(listOf(root, child))!!

        val found = SpanTree.findSpan(tree) { it.operationName == "child-op" }

        found.shouldNotBeNull()
        found.span.spanId shouldBe "child"
      }

      test("should return null when no match") {
        val root = createSpan(spanId = "root")
        val tree = SpanTree.build(listOf(root))!!

        val found = SpanTree.findSpan(tree) { it.operationName == "nonexistent" }

        found.shouldBeNull()
      }
    }

    context("SpanTree.filterSpans") {
      test("should filter spans matching predicate") {
        val root = createSpan(spanId = "root", status = SpanStatus.OK)
        val child1 = createSpan(spanId = "child1", status = SpanStatus.ERROR, parentSpanId = "root")
        val child2 = createSpan(spanId = "child2", status = SpanStatus.ERROR, parentSpanId = "root")
        val tree = SpanTree.build(listOf(root, child1, child2))!!

        val filtered = SpanTree.filterSpans(tree) { it.status == SpanStatus.ERROR }

        filtered shouldHaveSize 2
        filtered.map { it.span.spanId } shouldContainExactly listOf("child1", "child2")
      }

      test("should return empty list when no match") {
        val root = createSpan(spanId = "root", status = SpanStatus.OK)
        val tree = SpanTree.build(listOf(root))!!

        val filtered = SpanTree.filterSpans(tree) { it.status == SpanStatus.ERROR }

        filtered shouldHaveSize 0
      }
    }
  })

private fun createSpan(
  traceId: String = "trace123",
  spanId: String = "span456",
  parentSpanId: String? = null,
  operationName: String = "test-operation",
  serviceName: String = "test-service",
  startTimeNanos: Long = 0L,
  endTimeNanos: Long = 1_000_000L,
  status: SpanStatus = SpanStatus.OK,
  attributes: Map<String, String> = emptyMap(),
  exception: ExceptionInfo? = null
): SpanInfo = SpanInfo(
  traceId = traceId,
  spanId = spanId,
  parentSpanId = parentSpanId,
  operationName = operationName,
  serviceName = serviceName,
  startTimeNanos = startTimeNanos,
  endTimeNanos = endTimeNanos,
  status = status,
  attributes = attributes,
  exception = exception
)
