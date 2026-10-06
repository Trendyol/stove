package stove.ktor.bff.auth.storage.postgres

import org.flywaydb.core.api.ResourceProvider
import org.flywaydb.core.api.resource.LoadableResource
import java.io.Reader

/** Build-generated index avoids directory scanning, which is unavailable in a native image. */
internal class SessionMigrations : ResourceProvider {
  private val scripts = resource("db/bff/migrations.list").useLines { lines ->
    lines.filter(String::isNotBlank).associateWith(::Script)
  }.also { check(it.isNotEmpty()) { "BFF migration index is empty" } }

  override fun getResource(name: String): LoadableResource? = scripts[name]

  override fun getResources(prefix: String, suffixes: Array<out String>): Collection<LoadableResource> = scripts.values.filter { script ->
    script.filename.startsWith(prefix) && suffixes.any(script.filename::endsWith)
  }

  private class Script(private val path: String) : LoadableResource() {
    private val location = checkNotNull(SessionMigrations::class.java.classLoader.getResource(path)) {
      "Missing BFF migration resource: $path"
    }.toExternalForm()

    override fun getAbsolutePath(): String = path
    override fun getAbsolutePathOnDisk(): String = location
    override fun getFilename(): String = path.substringAfterLast('/')
    override fun getRelativePath(): String = path.removePrefix("db/bff/")
    override fun read(): Reader = resource(path)
  }

  companion object {
    private fun resource(path: String) = checkNotNull(SessionMigrations::class.java.classLoader.getResourceAsStream(path)) {
      "Missing BFF migration resource: $path"
    }.bufferedReader(Charsets.UTF_8)
  }
}
