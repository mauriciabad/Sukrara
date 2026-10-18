dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

// Investigation 26: the AGP report engine (plain JVM, shared semantics with Sukrara's GlucoseStats) + page renderer
rootProject.name = "agp-report"
