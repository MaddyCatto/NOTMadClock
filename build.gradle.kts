plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
}

// Resolved coordinates, including transitive libraries. This runs only during development/CI.
tasks.register("securityDependencyInventory") {
    doLast {
        val entries = sortedMapOf<String, MutableSet<String>>()
        fun collect(configuration: org.gradle.api.artifacts.Configuration, scope: String) {
            configuration.incoming.resolutionResult.allComponents.forEach { component ->
                val id = component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
                if (id != null) entries.getOrPut("${id.group}:${id.module}@${id.version}") { sortedSetOf() }.add(scope)
            }
        }
        subprojects.forEach { child ->
            listOf("debugRuntimeClasspath", "releaseRuntimeClasspath", "debugUnitTestRuntimeClasspath").forEach { name ->
                collect(child.configurations.getByName(name), "${child.name}:$name")
            }
        }
        collect(buildscript.configurations.getByName("classpath"), "build-tools")
        val result = entries.map { (coordinate, scopes) -> mapOf("coordinate" to coordinate, "scopes" to scopes.toList()) }
        val output = layout.buildDirectory.file("reports/security/dependencies.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result)))
        println("Resolved dependency inventory: ${result.size} coordinates")
    }
}
