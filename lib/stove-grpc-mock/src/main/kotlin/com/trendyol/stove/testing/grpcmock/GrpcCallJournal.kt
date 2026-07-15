package com.trendyol.stove.testing.grpcmock

import io.grpc.Metadata
import java.util.concurrent.*

/**
 * Test-scoped journal of requests received by the mock server.
 *
 * Scoping is fail-open: a request is excluded from a test's view only when it is
 * provably tagged with a different test id. Untagged requests are visible to every
 * test, so applications without test-id propagation behave as if scoping did not exist.
 */
internal class GrpcCallJournal {
  private val taggedRequests = ConcurrentHashMap<String, CopyOnWriteArrayList<ReceivedRequest>>()
  private val untaggedRequests = CopyOnWriteArrayList<ReceivedRequest>()

  fun record(request: ReceivedRequest) {
    when (val testId = request.testId) {
      null -> untaggedRequests.add(request)
      else -> taggedRequests.computeIfAbsent(testId) { CopyOnWriteArrayList() }.add(request)
    }
  }

  /** Requests visible to the given test: its own plus every untagged request. */
  fun requests(testId: String): List<ReceivedRequest> =
    untaggedRequests.toList() + (taggedRequests[testId]?.toList() ?: emptyList())

  fun clear(testId: String) {
    taggedRequests.remove(testId)
  }

  fun clearAll() {
    taggedRequests.clear()
    untaggedRequests.clear()
  }
}

/** Converts gRPC metadata to a plain header map for Stove's test-id extraction. */
internal fun Metadata.toHeaderMap(): Map<String, String> =
  keys()
    .filterNot { it.endsWith(Metadata.BINARY_HEADER_SUFFIX) }
    .mapNotNull { name ->
      get(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER))?.let { name to it }
    }.toMap()
