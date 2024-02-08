import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.invoke

fun Project.subprojectsOf(
    vararg parentProjects: String,
    action: Action<Project>,
): Unit = subprojects.filter { parentProjects.contains(it.parent?.name) }.forEach { action(it) }

fun Collection<Project>.of(
    vararg parentProjects: String,
    except: List<String> = emptyList(),
    action: Action<Project>,
): Unit = this.filter {
    parentProjects.contains(it.parent?.name) && !except.contains(it.name)
}.forEach { action(it) }

fun Collection<Project>.of(
    vararg parentProjects: String,
    except: List<String> = emptyList(),
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

fun Project.testProjects(): List<Task> = listOfNotNull(
    this.tasks.firstOrNull { it.name.contains("test") },
    tasks.firstOrNull { it.name.contains("e2eTest") },
    tasks.firstOrNull { it.name.contains("integrationTest") }
)

fun Project.compilationTasks(): List<Task> = this.tasks.filter { it.name.contains("compile") }

fun Project.findTestProjectsRecursively(): List<Task> {
    val tasks = this.testProjects()
    if (tasks.isNotEmpty()) return tasks
    return this.subprojects.flatMap { it.findTestProjectsRecursively() }
}

fun Project.recursivelyContainsTask(name: String): Task? {
    val task = this.tasks.firstOrNull { it.name.contains(name) }
    if (task != null) return task
    return this.subprojects.firstNotNullOfOrNull { it.recursivelyContainsTask(name) }
}

fun Project.whenService(action: (Project) -> Unit): Unit = if (this.parent != null && this.parent!!.name == "projects") {
    action(this)
} else Unit

fun Project.goToService(): Project {
    if (this.parent != null && this.parent!!.name != "projects") {
        return this.parent!!.goToService()
    }

    if (this.parent != null && this.parent!!.name == "projects") {
        return this
    }

    throw IllegalStateException("Project is not a service")
}

fun Project.goToProjectLevel(of: String): Project {
    if (this.parent != null && this.parent!!.name != of) {
        return this.parent!!.goToProjectLevel(of)
    }

    if (this.parent != null && this.parent!!.name == of) {
        return this
    }

    throw IllegalStateException("Project is not a library")
}

