import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.invoke

fun Collection<Project>.of(
  vararg parentProjects: String,
  except: List<String> = emptyList(),
  action: Action<Project>
): Unit = this.filter {
  parentProjects.contains(it.parent?.name) && !except.contains(it.name)
}.forEach { action(it) }

fun Collection<Project>.of(
  vararg parentProjects: String,
  except: List<String> = emptyList()
): List<Project> = this.filter {
  parentProjects.contains(it.parent?.name) && !except.contains(it.name)
}

fun Collection<Project>.of(
  vararg parentProjects: String,
  action: Action<Project>
): Unit = this.filter { parentProjects.contains(it.parent?.name) }.forEach { action(it) }

fun Collection<Project>.of(
  vararg parentProjects: String,
  filter: (Project) -> Boolean,
  action: Action<Project>
): Unit = this.filter { parentProjects.contains(it.parent?.name) && filter(it) }.forEach { action(it) }

infix fun <T> Property<T>.by(value: T) {
  set(value)
}

fun nextPatchSnapshot(version: String): String {
  val (major, minor, patch) = version.split(".")
  return "$major.$minor.${patch.toInt() + 1}-SNAPSHOT"
}
