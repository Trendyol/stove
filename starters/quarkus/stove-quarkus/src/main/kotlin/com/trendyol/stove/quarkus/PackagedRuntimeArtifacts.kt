package com.trendyol.stove.quarkus

import java.io.File
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths

internal data class PackagedRuntimeArtifacts(
  val appRoot: Path,
  val applicationDat: Path,
  val bootUrls: Array<URL>
)

internal fun findPackagedRuntimeArtifacts(): PackagedRuntimeArtifacts? =
  resolveClassPathEntries()
    .mapNotNull { it.findBuildDirectory() }
    .distinct()
    .firstNotNullOfOrNull(::resolvePackagedRuntimeArtifacts)

private fun Path.findBuildDirectory(): Path? =
  generateSequence(this) { current -> current.parent }
    .firstOrNull { current -> current.fileName?.toString() == "build" }

private fun resolveClassPathEntries(): List<Path> {
  val pathSeparator = File.pathSeparator
  return System
    .getProperty("java.class.path")
    .split(pathSeparator)
    .map { Paths.get(it).toAbsolutePath().normalize() }
}

private fun resolvePackagedRuntimeArtifacts(buildDirectory: Path): PackagedRuntimeArtifacts? {
  val appRoot = buildDirectory.resolve("quarkus-app")
  val applicationDat = appRoot.resolve("quarkus/quarkus-application.dat")
  val bootUrls = resolveBootUrls(appRoot.resolve("lib/boot"))
  if (!applicationDat.toFile().exists() || bootUrls.isEmpty()) return null

  return PackagedRuntimeArtifacts(
    appRoot = appRoot,
    applicationDat = applicationDat,
    bootUrls = bootUrls.toTypedArray()
  )
}

private fun resolveBootUrls(bootDirectory: Path): List<URL> =
  bootDirectory
    .toFile()
    .listFiles { file -> file.extension == "jar" }
    ?.sortedBy { it.name }
    ?.map { it.toURI().toURL() }
    .orEmpty()
