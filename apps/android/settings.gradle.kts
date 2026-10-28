// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// The module list is owned by the scaffold (docs/ARCHITECTURE.md §Android): later work adds code to these modules,
// never new modules.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Sukrara"

include(":app")
include(":core:model", ":core:testing", ":core:alerts", ":core:protocol", ":core:data", ":core:designsystem", ":core:graph")
include(":monitor", ":sensor", ":share", ":integrations", ":pen", ":widgets")
include(":feature:home", ":feature:settings", ":feature:sharing", ":feature:sensor", ":feature:treatments")
