/*
 * agent-core — Shared engine modules for Hermes and Jeeves.
 *
 * Module layout:
 *   :core:util        — Pure Kotlin utilities, coroutine helpers
 *   :core:domain      — Domain models, interfaces, agent contracts
 *   :core:theme       — Compose theme, colors, accessibility helpers
 *   :core:plugin      — Plugin sandbox contracts and gRPC stub
 *   :core:settings    — Preferences, cloud config, build config
 *   :core:persistence — Room DAOs and entities (no @Database)
 *   :core:memory      — RAG, embeddings, ONNX runtime
 *   :core:llm         — LLM routing, cloud/local providers, inference
 *   :core:tools       — Tool implementations (shell, web, file, etc.)
 */

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
        // Termux terminal engine (terminal-view / terminal-emulator) is
        // published from github.com/termux/termux-app via JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "agent-core"
include(":core:util")
include(":core:domain")
include(":core:theme")
include(":core:plugin")
include(":core:settings")
include(":core:persistence")
include(":core:memory")
include(":core:llm")
include(":core:tools")
