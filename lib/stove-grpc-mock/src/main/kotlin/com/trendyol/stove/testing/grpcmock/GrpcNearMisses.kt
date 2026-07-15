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
    val requestOk = definition.requestMatcher.matches(requestBytes)
    val metadataOk = definition.metadataMatcher.matches(metadata)
    val reason = when {
      !requestOk && !metadataOk ->
        "request matcher rejected (${definition.requestMatcher.describeRejection(requestBytes)}) " +
          "and metadata matcher rejected (${definition.metadataMatcher.describe()})"

      !requestOk -> "request matcher rejected: ${definition.requestMatcher.describeRejection(requestBytes)}"

      !metadataOk -> "metadata matcher rejected: ${definition.metadataMatcher.describe()}"

      else -> "matches now, but was consumed or overridden when the request arrived"
    }
    "${definition::class.simpleName} stub: $reason"
  }
}

private fun RequestMatcher.describeRejection(requestBytes: ByteArray): String = when (this) {
  is RequestMatcher.Any -> "matches any request"

  is RequestMatcher.ExactBytes -> "expected ${bytes.size} exact bytes, received ${requestBytes.size}"

  is RequestMatcher.Custom -> "custom request matcher returned false"

  is RequestMatcher.ExactMessage -> {
    val received = runCatching { message.parserForType.parseFrom(requestBytes) }.getOrNull()
    "expected message { ${message.singleLine()} } but received { ${received?.singleLine() ?: "unparseable bytes"} }"
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

private fun Message.singleLine(): String = toString().replace(Regex("\\s+"), " ").trim()
