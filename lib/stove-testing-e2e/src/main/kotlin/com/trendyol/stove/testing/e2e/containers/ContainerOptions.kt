package com.trendyol.stove.testing.e2e.containers

interface ContainerOptions {

    val registry: String

    val image: String

    val tag: String

    val imageWithTag: String
}
