import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

val allowedProjects = mapOf(
    ":core:domain" to emptySet(),
    ":core:application" to setOf(":core:domain"),
    ":protocol" to emptySet(),
    ":adapters:android" to setOf(":core:application", ":core:domain"),
    ":adapters:persistence" to setOf(":core:application", ":core:domain"),
    ":adapters:transport" to setOf(":core:application", ":core:domain", ":protocol"),
    ":feature:control" to setOf(":core:application", ":core:domain"),
    ":testing:fixtures" to setOf(":core:application", ":core:domain"),
    ":app" to setOf(":core:application", ":core:domain", ":protocol", ":adapters:android", ":adapters:persistence", ":adapters:transport", ":feature:control"),
)
val pureLibraries = mapOf(
    ":core:domain" to setOf("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations"),
    ":core:application" to setOf("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core", "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
        "org.jetbrains.kotlinx:kotlinx-coroutines-bom"),
)

val checkSourceBoundaries = tasks.register<Exec>("checkSourceBoundaries") {
    group = "verification"
    description = "Reject forbidden imports/APIs and outward source references."
    commandLine("python3", rootProject.file("tools/check_architecture.py"))
}
val checkArchitecture = tasks.register("checkArchitecture") {
    group = "verification"
    description = "Enforce inward project dependencies and the resolved pure-core classpaths."
    dependsOn(checkSourceBoundaries)
    doLast {
        allprojects.filter { it.path in allowedProjects }.forEach { module ->
            module.configurations.filter {
                !it.name.contains("test", ignoreCase = true) &&
                    it.name.matches(Regex(".*(?:api|implementation|compileOnly|runtimeOnly)", RegexOption.IGNORE_CASE))
            }.forEach { configuration ->
                configuration.dependencies.forEach { dependency ->
                    if (dependency is ProjectDependency) {
                        check(dependency.dependencyProject.path in allowedProjects.getValue(module.path)) {
                            "Architecture violation: ${module.path}:${configuration.name} -> ${dependency.dependencyProject.path}"
                        }
                    } else if (module.path in pureLibraries) {
                        check("${dependency.group}:${dependency.name}" in pureLibraries.getValue(module.path)) {
                            "Architecture violation: ${module.path} declares ${dependency.group}:${dependency.name}"
                        }
                    }
                }
            }
            if (module.path in pureLibraries) {
                listOf("compileClasspath", "runtimeClasspath").forEach { name ->
                    module.configurations.getByName(name).incoming.resolutionResult.allComponents.forEach { component ->
                        val id = component.id
                        if (id is ModuleComponentIdentifier) check("${id.group}:${id.module}" in pureLibraries.getValue(module.path)) {
                            "Architecture violation: ${module.path}:$name leaks ${id.group}:${id.module}"
                        }
                    }
                }
            }
        }
        logger.lifecycle("Project dependencies and resolved core classpaths obey the architecture.")
    }
}
tasks.named("check") { dependsOn(checkArchitecture) }
// A module-specific check must enforce the same boundary as the root/CI check.
subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(rootProject.tasks.named("checkArchitecture")) }
}
