package com.trendyol.stove.containers

import org.testcontainers.utility.DockerImageName

typealias ContainerFn<TIn> = TIn.() -> Unit

typealias UseContainerFn<TContainer> = (DockerImageName) -> TContainer

/**
 * Container options to run
 */
interface ContainerOptions<TContainer : StoveContainer> {
  val registry: String

  val image: String

  val tag: String

  val imageWithTag: String get() = "$image:$tag"

  val compatibleSubstitute: String?

  val useContainerFn: UseContainerFn<TContainer>

  val containerFn: ContainerFn<TContainer>
}
