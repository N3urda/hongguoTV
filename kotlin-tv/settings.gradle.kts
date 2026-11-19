pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "HongguoTVNative"
include(":shared-core", ":core", ":app", ":decoder-ffmpeg")
// Android and core-only invocations must not resolve the desktop plugin toolchain.
val requestsDesktop = gradle.startParameter.taskNames.any { it.removePrefix(":").startsWith("app-desktop:") }
if (requestsDesktop || providers.gradleProperty("includeDesktop").isPresent) {
    includeBuild(".")
    includeBuild("app-desktop")
}
