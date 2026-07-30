package com.trendyol.stove.wiremock

/** Restricts implicit receiver scope while configuring WireMock DSL blocks. */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPEALIAS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION)
annotation class WiremockDsl
