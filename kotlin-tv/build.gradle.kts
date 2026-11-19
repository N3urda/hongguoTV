plugins {
    id("com.android.library") version "8.11.0" apply false
    id("com.android.application") version "8.11.0" apply false
    kotlin("android") version "2.1.20" apply false
    kotlin("jvm") version "2.1.20" apply false
    kotlin("multiplatform") version "2.1.20" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}

// UI modules may consume the cores. A core or sibling UI must never pull a frontend back in.
val frontendModules = setOf(":app", ":app-desktop")
subprojects {
    configurations.configureEach {
        dependencies.withType<ProjectDependency>().configureEach {
            check(path == project.path || path !in frontendModules) {
                "${project.path} cannot depend on frontend module $path"
            }
        }
    }
}
