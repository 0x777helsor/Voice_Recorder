// SafeSignal — Gradle settings.
//
// Module layout rationale is documented in ARCHITECTURE.md § "Module graph".
// The layout follows the structure recommended by the SafeSignal specification
// (core / audio / data / service / feature / app) but consolidates closely
// coupled packages into single modules to keep the build graph small and
// incremental builds fast.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SafeSignal"

include(":app")
include(":service")
include(":core:common")
include(":core:crypto")
include(":core:database")
include(":audio:capture")
include(":audio:wakeword")
include(":audio:processing")
include(":data:local")
include(":data:remote")
include(":data:repository")
include(":feature:onboarding")
include(":feature:emergency")
include(":feature:recordings")
include(":feature:settings")