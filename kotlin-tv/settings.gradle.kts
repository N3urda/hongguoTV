pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "HongguoTVNative"
include(":shared-core", ":core", ":app", ":decoder-ffmpeg")
includeBuild(".")
includeBuild("app-desktop")
