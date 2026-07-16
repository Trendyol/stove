package com.trendyol.stove.testing.grpcmock

import com.google.protobuf.Message
import io.grpc.Metadata

/**
 * A journaled request together with the reasons each candidate stub rejected it,
 * captured at request time so diagnostics survive stub removal.
 */
internal data class JournaledRequest(
  val request: ReceivedRequest,
  val nearMisses: List<String> = emptyList()
)

/** Explains why each candidate stub rejected an unmatched request. */
internal fun List<RegisteredStub>.diagnoseRejections(
  requestBytes: ByteArray,
  metadata: Metadata
): List<String> {
  if (isEmpty()) return listOf("no stubs registered for this method")
  return map { registered ->
    val definition = registered.definition
    val requestEvaluation = definition.requestMatcher.evaluate(requestBytes)
    val requestOk = requestEvaluation.matched
    val metadataOk = definition.metadataMatcher.matches(metadata)
    val reason = when {
      !requestOk && !metadataOk ->
        "request matcher rejected (${requestEvaluation.rejection}) " +
          "and metadata matcher rejected (${definition.metadataMatcher.describe()})"

      !requestOk -> "request matcher rejected: ${requestEvaluation.rejection}"

      !metadataOk -> "metadata matcher rejected: ${definition.metadataMatcher.describe()}"

      else -> "matches now, but was consumed or overridden when the request arrived"
    }
    "${definition::class.simpleName} stub: $reason"
  }
}

private fun MetadataMatcher.describe(): String = when (this) {
  is MetadataMatcher.Any -> "matches any metadata"
  is MetadataMatcher.HasHeader -> "requires header '$key'"
  is MetadataMatcher.BearerToken -> "requires a specific Bearer token"
  is MetadataMatcher.RequiresAuth -> "requires an authorization header"
  is MetadataMatcher.Custom -> "custom metadata matcher returned false"
  is MetadataMatcher.All -> matchers.joinToString(" and ") { it.describe() }
}
