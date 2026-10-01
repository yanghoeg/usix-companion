pluginManagement {
    repositories {
        google()
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

rootProject.name = "usix-companion"
include(":core:domain", ":core:application", ":protocol", ":adapters:transport", ":testing:fixtures")
// No Android projects are configured in this profile, even on hosts without an SDK.
if (!providers.gradleProperty("coreOnly").map(String::toBoolean).getOrElse(false)) {
    include(":app", ":adapters:android", ":adapters:persistence", ":feature:control")
}
