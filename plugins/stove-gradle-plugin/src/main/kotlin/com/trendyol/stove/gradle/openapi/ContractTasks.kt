package com.trendyol.stove.gradle.openapi

import com.trendyol.stove.gradle.StoveWiremockExtension
import com.trendyol.stove.gradle.internal.StovePluginDefaults
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.*
import javax.inject.Inject

internal fun configureWiremock(project: Project, wiremock: StoveWiremockExtension) = with(project) {
  afterEvaluate {
    if (wiremock.contracts.isEmpty()) return@afterEvaluate
    require(pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")) { "Stove WireMock generation requires the Kotlin JVM plugin" }
    val source = extensions.getByType(SourceSetContainer::class.java).getByName(wiremock.sourceSet.get())
    val kotlin = source.extensions.getByName("kotlin") as SourceDirectorySet
    val version = StovePluginDefaults.openapiGeneratorVersion
    val generator = configurations.create("stoveOpenApiGenerator") {
      isCanBeConsumed = false
    }
    dependencies.add(generator.name, "org.openapitools:openapi-generator:$version")
    wiremock.contracts.forEach { (alias, spec) ->
      require(listOf(spec.specFile, spec.specText, spec.specUrl).count { it.isPresent } == 1) {
        "Stove WireMock '$alias': supply exactly one of specFile, specText, or specUrl"
      }
      val title = alias.replaceFirstChar(Char::uppercase)
      val offlineCache = layout.projectDirectory.file(".gradle/stove/openapi/$alias.json")
      val resolve = tasks.register("resolve${title}StoveContract", ResolveStoveContract::class.java) {
        toolClasspath.from(generator)
        location.set(spec.specUrl.orElse(spec.specFile.map { it.asFile.toURI().toString() })
          .orElse(layout.projectDirectory.file("inline-openapi.yaml").asFile.toURI().toString()))
        content.set(spec.specText)
        offline.set(gradle.startParameter.isOffline)
        remoteCache.set(offlineCache)
        downloads.set(layout.buildDirectory.file("stove/openapi/$alias-downloads.json"))
        snapshot.set(layout.buildDirectory.file("stove/openapi/$alias.json"))
      }
      val generate = tasks.register("generate${title}StoveWiremockSources", GenerateStoveContract::class.java) {
        toolClasspath.from(generator)
        contract.set(resolve.flatMap { it.snapshot })
        apiName.set(alias)
        mappings.set(spec.schemaMappings)
        destination.set(layout.buildDirectory.dir("generated/stove/wiremock/$alias"))
        skipped.set(layout.buildDirectory.file("stove/openapi/$alias-skipped.txt"))
      }
      // Commit downloads only after the Kotlin sources were generated (or restored from Gradle's build cache).
      val complete = tasks.register("generate${title}StoveWiremock", CacheStoveContract::class.java) {
        skipped.set(generate.flatMap { it.skipped })
        downloads.set(resolve.flatMap { it.downloads })
        remoteCache.set(offlineCache)
      }
      kotlin.srcDir(files(generate.flatMap { it.destination }).builtBy(complete))
    }
  }
  Unit
}

@DisableCachingByDefault(because = "Always revalidate referenced documents, including remote contracts")
abstract class ResolveStoveContract @Inject constructor(private val workers: WorkerExecutor) : DefaultTask() {
  @get:Classpath abstract val toolClasspath: ConfigurableFileCollection
  @get:Input abstract val location: Property<String>
  @get:Input @get:Optional abstract val content: Property<String>
  @get:Input abstract val offline: Property<Boolean>
  @get:Internal abstract val remoteCache: RegularFileProperty
  @get:OutputFile abstract val downloads: RegularFileProperty
  @get:OutputFile abstract val snapshot: RegularFileProperty

  init { outputs.upToDateWhen { false } }

  @TaskAction fun resolve() {
    workers.classLoaderIsolation { classpath.from(toolClasspath) }.submit(ResolveContractWork::class.java) {
      uri.set(location)
      inline.set(content)
      offlineMode.set(offline)
      cache.set(remoteCache)
      output.set(snapshot)
      downloads.set(this@ResolveStoveContract.downloads)
    }
  }
}

@CacheableTask
abstract class GenerateStoveContract @Inject constructor(private val workers: WorkerExecutor) : DefaultTask() {
  @get:Classpath abstract val toolClasspath: ConfigurableFileCollection
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val contract: RegularFileProperty
  @get:Input abstract val apiName: Property<String>
  @get:Input abstract val mappings: MapProperty<String, String>
  @get:OutputDirectory abstract val destination: DirectoryProperty
  @get:OutputFile abstract val skipped: RegularFileProperty

  @TaskAction fun generate() {
    workers.classLoaderIsolation { classpath.from(toolClasspath) }.submit(GenerateContractWork::class.java) {
      input.set(contract)
      alias.set(apiName)
      types.set(mappings)
      output.set(destination)
      skipped.set(this@GenerateStoveContract.skipped)
      scratch.set(temporaryDir)
    }
  }
}

@DisableCachingByDefault(because = "Copies the last successful download into the project's offline cache")
abstract class CacheStoveContract : DefaultTask() {
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val downloads: RegularFileProperty
  @get:OutputFile abstract val remoteCache: RegularFileProperty
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val skipped: RegularFileProperty

  // Generation is cached, so skipped operations are reported here on every build.
  init { outputs.upToDateWhen { false } }

  @TaskAction fun remember() {
    skipped.asFile.get().forEachLine(action = logger::warn)
    writeChanged(remoteCache.asFile.get(), downloads.asFile.get().readBytes())
  }
}
