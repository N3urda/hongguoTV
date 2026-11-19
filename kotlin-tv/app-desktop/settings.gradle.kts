pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }
dependencyResolutionManagement { repositories { mavenCentral(); google() } }
rootProject.name = "app-desktop"
// Opening the desktop directory directly (for example in an IDE) includes only its core provider.
if (gradle.parent == null) includeBuild("..")
