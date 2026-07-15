package com.trendyol.stove.testing.grpcmock

import io.grpc.Metadata

/** Converts gRPC metadata to a plain header map for Stove's test-id extraction. */
internal fun Metadata.toHeaderMap(): Map<String, String> =
  keys()
    .filterNot { it.endsWith(Metadata.BINARY_HEADER_SUFFIX) }
    .mapNotNull { name ->
      get(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER))?.let { name to it }
    }.toMap()
