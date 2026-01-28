package com.trendyol.stove.tracing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class SpanTreeTest :
  FunSpec({

    test("build should return null for empty list") {
      val tree = SpanTree.build(emptyList())

      tree.shouldBeNull()
    }

    test("build should create single node for single span") {
      val span = createSpan(spanId = "root", parentSpanId = null)

      val tree = SpanTree.build(listOf(span))

      tree.shouldNotBeNull()
      tree.span.spanId shouldBe "root"
      tree.children.shouldHaveSize(0)
    }

    test("build should create proper parent-child relationships") {
      val spans = listOf(
        createSpan(spanId = "root", parentSpanId = null),
        createSpan(spanId = "child1", parentSpanId = "root"),
        createSpan(spanId = "child2", parentSpanId = "root"),
        createSpan(spanId = "grandchild", parentSpanId = "child1")
      )

      val tree = SpanTree.build(spans)

      tree.shouldNotBeNull()
      tree.span.spanId shouldBe "root"
      tree.children shouldHaveSize 2

      val child1 = tree.children.find { it.span.spanId == "child1" }
      child1.shouldNotBeNull()
      child1.children shouldHaveSize 1
      child1.children[0].span.spanId shouldBe "grandchild"
    }

    test("build should sort children by start time") {
      val spans = listOf(
        createSpan(spanId = "root", parentSpanId = null, startTimeNanos = 1000),
        createSpan(spanId = "child3", parentSpanId = "root", startTimeNanos = 3000),
        createSpan(spanId = "child1", parentSpanId = "root", startTimeNanos = 1000),
        createSpan(spanId = "child2", parentSpanId = "root", startTimeNanos = 2000)
      )

      val tree = SpanTree.build(spans)

      tree.shouldNotBeNull()
      tree.children[0].span.spanId shouldBe "child1"
      tree.children[1].span.spanId shouldBe "child2"
      tree.children[2].span.spanId shouldBe "child3"
    }

    test("SpanNode.hasFailedDescendants should detect failures in subtree") {
      val spans = listOf(
        createSpan(spanId = "root", parentSpanId = null, status = SpanStatus.OK),
        createSpan(spanId = "child", parentSpanId = "root", status = SpanStatus.OK),
        createSpan(spanId = "grandchild", parentSpanId = "child", status = SpanStatus.ERROR)
      )

      val tree = SpanTree.build(spans)

      tree.shouldNotBeNull()
      tree.hasFailedDescendants shouldBe true
      tree.span.isFailed shouldBe false
    }

    test("SpanNode.depth should calculate correct depth") {
      val spans = listOf(
        createSpan(spanId = "root", parentSpanId = null),
        createSpan(spanId = "child", parentSpanId = "root"),
        createSpan(spanId = "grandchild", parentSpanId = "child")
      )

      val tree = SpanTree.build(spans)

      tree.shouldNotBeNull()
      tree.depth shouldBe 3
    }

    test("SpanNode.spanCount should count all nodes") {
      val spans = listOf(
        createSpan(spanId = "root", parentSpanId = null),
        createSpan(spanId = "child1", parentSpanId = "root"),
        createSpan(spanId = "child2", parentSpanId = "root"),
        createSpan(spanId = "grandchild", parentSpanId = "child1")
      )

      val tree = SpanTree.build(spans)

      tree.shouldNotBeNull()
      tree.spanCount shouldBe 4
    }

    test("SpanNode.findFailurePoint should find deepest failure") {
      val spans = listOf(
        createSpan(spanId = "root", parentSpanId = null, status = SpanStatus.OK),
        createSpan(spanId = "child", parentSpanId = "root", status = SpanStatus.ERROR),
        createSpan(spanId = "grandchild", parentSpanId = "child", status = SpanStatus.ERROR)
      )

      val tree = SpanTree.build(spans)

      tree.shouldNotBeNull()
      val failurePoint = tree.findFailurePoint()
      failurePoint.shouldNotBeNull()
      failurePoint.span.spanId shouldBe "grandchild"
    }

    test("SpanNode.flatten should return all spans in order") {
      val spans = listOf(
        createSpan(spanId = "root", parentSpanId = null),
        createSpan(spanId = "child1", parentSpanId = "root"),
        createSpan(spanId = "child2", parentSpanId = "root")
      )

      val tree = SpanTree.build(spans)

      tree.shouldNotBeNull()
      val flattened = tree.flatten()
      flattened shouldHaveSize 3
      flattened[0].spanId shouldBe "root"
    }

    test("findSpan should locate span by predicate") {
      val spans = listOf(
        createSpan(spanId = "root", parentSpanId = null, operationName = "root.op"),
        createSpan(spanId = "child", parentSpanId = "root", operationName = "child.op")
      )

      val tree = SpanTree.build(spans)!!

      val found = SpanTree.findSpan(tree) { it.operationName == "child.op" }

      found.shouldNotBeNull()
      found.span.spanId shouldBe "child"
    }

    test("filterSpans should return all matching spans") {
      val spans = listOf(
        createSpan(spanId = "root", parentSpanId = null, status = SpanStatus.OK),
        createSpan(spanId = "child1", parentSpanId = "root", status = SpanStatus.ERROR),
        createSpan(spanId = "child2", parentSpanId = "root", status = SpanStatus.ERROR)
      )

      val tree = SpanTree.build(spans)!!

      val failed = SpanTree.filterSpans(tree) { it.isFailed }

      failed shouldHaveSize 2
    }
  })

private fun createSpan(
  traceId: String = "trace123",
  spanId: String = "span123",
  parentSpanId: String? = null,
  operationName: String = "test.operation",
  serviceName: String = "test-service",
  startTimeNanos: Long = 1_000_000_000L,
  endTimeNanos: Long = 1_100_000_000L,
  status: SpanStatus = SpanStatus.OK
) = SpanInfo(
  traceId = traceId,
  spanId = spanId,
  parentSpanId = parentSpanId,
  operationName = operationName,
  serviceName = serviceName,
  startTimeNanos = startTimeNanos,
  endTimeNanos = endTimeNanos,
  status = status
)
