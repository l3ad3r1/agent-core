# Hermes / Jeeves Shared Agent Core

This repository contains the shared Kotlin/Android engine consumed by the public Hermes
app and the private Jeeves app. Both products map their `:core:*` Gradle projects to this
source tree so domain contracts, plugin delivery, LLM routing, tools, memory, persistence,
and settings evolve together.

## Modules

- `core:domain` — stable contracts and domain models.
- `core:plugin` — catalog codec, APK verification/download, install review, and completion.
- `core:llm` — provider contracts and local/cloud routing.
- `core:tools` — deterministic phone tools and approval boundaries.
- `core:memory`, `core:persistence`, `core:settings`, `core:theme`, `core:util` — shared
  storage, settings, presentation, and platform support.

## Build and test

```powershell
.\gradlew.bat :core:domain:test :core:plugin:test
```

Hermes and Jeeves use a composite checkout during development. From either app checkout,
place this repository beside the app directory as `../agent-core`, then run the app's
normal Gradle tests or release build.

## Consumer pinning (`agent-core.ref`)

Each host app pins the agent-core commit it builds against in a top-level
`agent-core.ref` file. That pin is **load-bearing, not documentation**: the apps'
CI and release workflows check this repository out at exactly that commit. Local
builds do not — they map `:core:*` straight onto a working tree — so a mismatch
is invisible until CI runs.

The rule that follows: **a change to a shared API or JNI signature here, and the
app-side change that depends on it, must be repinned in the same change.** Bump
`agent-core.ref` in every consuming app in the commit that needs the new engine.

Skipping this is quiet. v1.0.2 of both apps shipped with red CI for exactly this
reason — the apps had moved to a `ConversationCompressor.brief()` without its
`anchorId` parameter while still pinned to a commit that required it, so CI
compiled new app code against old engine code and failed on a signature nothing
locally disagreed with.

## Plugin modules

The host products expose **Settings → Features → Modules**. Public module publishing and
the catalog authoring guide live in the [Hermes/Jeeves Modules repository](https://github.com/l3ad3r1/hermes-jeeves-modules).
The full catalog, package, signing, trust, and installer contract is documented in
[`docs/PLUGIN_REPOSITORY.md`](docs/PLUGIN_REPOSITORY.md).

## Contribution rules

Keep domain contracts platform-neutral, inject infrastructure behind interfaces, preserve
fail-closed verification, and add tests for persistence, cancellation, idempotence, and
failure paths. Do not commit signing credentials, APK secrets, or local machine properties.

