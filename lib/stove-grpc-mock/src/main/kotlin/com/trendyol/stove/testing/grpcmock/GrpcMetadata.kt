package com.trendyol.stove.testing.grpcmock

import io.grpc.Metadata

/** Converts gRPC metadata to a plain header map for Stove's test-id extraction. */
internal fun Metadata.toHeaderMap(): Map<String, String> =
  keys()
    .filterNot { it.endsWith(Metadata.BINARY_HEADER_SUFFIX) }
    .mapNotNull { name ->
      val key = Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER)
      val value = if (name.equals("baggage", ignoreCase = true)) {
        getAll(key)?.joinToString(",")
      } else {
        get(key)
      }
      value?.let { name to it }
    }.toMap()
