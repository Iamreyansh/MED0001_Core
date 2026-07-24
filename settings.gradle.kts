pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "med0001-core"

include(
    "platform:kernel",
    "platform:security",
    "platform:persistence",
    "platform:messaging",
    "platform:observability",
    "domains:auth",
    "domains:customer",
    "domains:pharmacy",
    "domains:catalogue",
    "domains:inventory",
    "domains:pos",
    "domains:prescription",
    "domains:teleconsult",
    "domains:order",
    "domains:rider",
    "domains:payment",
    "domains:marketing",
    "domains:crm",
    "domains:support",
    "domains:analytics",
    "domains:notification",
    "domains:medicine-schedule",
    "domains:automation",
    "domains:observability-ops",
    "domains:settings",
    "domains:integration",
    "apps:api",
    "apps:worker",
    "testing",
)
