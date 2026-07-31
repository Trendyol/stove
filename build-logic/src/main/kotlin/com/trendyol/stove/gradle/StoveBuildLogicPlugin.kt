package com.trendyol.stove.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/** Makes Stove's shared build logic available to the main build scripts. */
class StoveBuildLogicPlugin : Plugin<Project> {
  override fun apply(target: Project) = Unit
}
