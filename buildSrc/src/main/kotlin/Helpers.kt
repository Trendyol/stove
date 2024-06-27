import org.gradle.api.*
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

val runningOnCI: Boolean
  get() = System.getenv("CI") != null
    || System.getenv("GITHUB_ACTIONS") != null
    || System.getenv("GITLAB_CI") != null
    || System.getenv("CIRCLECI") != null
    || System.getenv("TRAVIS") != null
    || System.getenv("TEAMCITY_VERSION") != null
    || System.getenv("JENKINS_URL") != null
