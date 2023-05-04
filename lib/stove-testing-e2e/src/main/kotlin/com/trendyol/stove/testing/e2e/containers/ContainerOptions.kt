package com.trendyol.stove.testing.e2e.containers

/**
 * Container options to run
 */
interface ContainerOptions {

    val registry: String

    val image: String

    val tag: String

    val imageWithTag: String

    val compatibleSubstitute: String get() = image
}
