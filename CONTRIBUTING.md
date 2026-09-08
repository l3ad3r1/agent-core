# Contributing to agent-core

This is the shared engine behind two Android apps. A change here lands in both,
so the bar is "would this be right for a product I haven't thought about yet".

All skill levels welcome. If you are looking for somewhere to start, the
[Hermes issue tracker](https://github.com/l3ad3r1/Hermes-Agent-Android/issues)
carries the engine work under the `llm-backend` and `plugin` labels — issues live
there rather than here so contributors have one queue to watch.

## Setting up

The engine is not vendored into the apps; they resolve it as a sibling checkout.
Clone both side by side:

```bash
git clone https://github.com/l3ad3r1/agent-core.git
git clone https://github.com/l3ad3r1/Hermes-Agent-Android.git
cd Hermes-Agent-Android
git submodule update --init          # pinned llama.cpp, needed for the native build
./gradlew :app:assembleDebug
```

Requirements: **JDK 21** (JetBrains Runtime), Android SDK with **NDK 28.2** and
CMake for the native build, `minSdk 29`. The app builds and installs with no API
key; to get an actual reply, add a cloud provider in Settings or download an
on-device model from the in-app catalogue.

Run the engine tests from either checkout:

```bash
./gradlew :core:llm:testDebugUnitTest :core:domain:test
```

## The one thing that will catch you

Each app pins the engine commit it builds against in `agent-core.ref`, and **CI
honours that pin while your local build ignores it** — locally, `:core:*` maps
straight onto your working tree.

So if your change touches a shared API or a JNI signature *and* its caller in an
app, you must bump `agent-core.ref` in the same pull request. Otherwise it
compiles cleanly for you and fails in CI on a signature nothing locally
disagrees with. Both apps shipped v1.0.2 with red CI for precisely this reason.

If your change is engine-only and source-compatible, no repin is needed.

## What the engine expects of you

- **`core:domain` stays platform-neutral.** No Android imports. If you need a
  platform capability, define the contract in `domain` and implement it in a
  `data` module. This is what keeps a desktop target a port rather than a rewrite.
- **Infrastructure goes behind an interface.** `EmbeddingService`, `VectorStore`,
  `LlmProvider` and `PluginSandbox` are the seams — swapping an implementation
  should not touch anything above it.
- **Verification stays fail-closed.** Signature, trust and sandbox checks must
  reject on error, never fall through to "allow".
- **Tests for persistence, cancellation, idempotence and failure paths.** Those
  are where this codebase has historically broken, not the happy path.
- **Never commit** signing credentials, keystores, API keys or local machine
  properties.

## Native code lives in the apps, not here

`ai_chat.cpp` — the `llama.cpp` JNI bridge — is in each app repo under
`app/src/main/cpp/`, and is **byte-identical between them**. A change to it must
be ported to both in the same change, and if it alters a JNI signature, the
Kotlin declarations here have to move with it. `md5sum` both files before you
open the PR.

## Pull requests

- One logical change per PR.
- Branch names: `fix/<short-description>` or `feat/<short-description>`; target `main`.
- Run `./gradlew test` before pushing; fix errors, warnings are advisory.
- Commit subject in the present tense, ≤72 chars ("Add X", not "Added X").
- Say in the description whether a consumer repin is needed, and why.

## Where the deep work is

- **Persistent vector store** — `InMemoryVectorStore` loses everything on process
  death. A sqlite-vec/SQLite-VSS backend behind `VectorStore` is the highest-value
  change available.
- **Ship the embedding model** — `MiniLmEmbeddingService` is real and bound, but
  reads its ONNX model from shared storage and silently falls back to hash
  vectors when it is missing. Nothing downloads it. Wiring it into the model
  catalogue would make good retrieval the default instead of a lucky accident.
- **Model-agnostic tool calling** — local tool-call parsing still has
  format-specific fallbacks.
- **A gRPC plugin transport.** The sandbox and registry are real and bound, but
  `GrpcPluginTransportModule` declares an empty `@Multibinds` set, so nothing can
  run out-of-process. Implementing a transport is the whole job.
