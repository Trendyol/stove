package com.trendyol.stove.grpc

/**
 * DSL marker for gRPC testing operations.
 *
 * This annotation is used to scope the DSL functions and prevent
 * accidental nesting of incompatible DSL blocks.
 */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPEALIAS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION)
annotation class GrpcDsl
