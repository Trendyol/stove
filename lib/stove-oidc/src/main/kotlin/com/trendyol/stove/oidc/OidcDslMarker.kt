package com.trendyol.stove.oidc

/** Restricts implicit receivers to the current OIDC configuration or operation. */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION)
annotation class OidcDsl
