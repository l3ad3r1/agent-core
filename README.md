# agent-core — the shared Hermes / Jeeves engine

The Kotlin engine behind two shipping Android apps: **[Hermes Agent](https://github.com/l3ad3r1/Hermes-Agent-Android)**
(`com.hermes.agent`) and **[Jeeves](https://github.com/l3ad3r1/Jeeves)** (`com.jeeves.app`).
Both map their `:core:*` Gradle projects onto this source tree, so model routing,
tools, memory, persistence and settings are written once and change in one place.

Nothing here is Android-app-specific: no UI, no app identity, no signing. That
separation is deliberate — it is what keeps a desktop or second-platform target a
port rather than a rewrite.

## Direction

The engine's current priorities, roughly in order:

1. **Make on-device inference genuinely usable.** The last two app releases were
   almost entirely this: KV prefix reuse, separate KV lanes for background work,
   and one model slot per role so a tool call stops evicting the chat model.
   Prefill on a long thread is ~9× faster than it was.
2. **Retrieval that survives a restart.** Real MiniLM embeddings are wired; the
   vector index is still in-memory. See *Known limitations* below.
3. **Keep the engine platform-neutral** so the Compose Multiplatform desktop
   target stays viable.

## Modules

| Module | Contents |
|---|---|
| `core:domain` | Platform-neutral contracts and domain models. No Android imports. |
| `core:llm` | Provider contracts, cloud/local routing, the local `llama.cpp` bridge, conversation compression. |
| `core:tools` | Deterministic phone tools and the approval boundary. |
| `core:memory` | Embeddings, vector store, RAG pipeline, memory consolidation. |
| `core:persistence` | Room entities, DAOs and migrations. |
| `core:plugin` | Script-plugin engine plus the signed native-module catalog, verification and install review. |
| `core:settings` | Settings repository and encrypted credential storage. |
| `core:theme`, `core:util` | Shared presentation and platform support. |

Every module has a test source set.

## Using it

This repository is **not vendored** into the apps. Each app resolves it as a
sibling checkout, so clone them side by side:

```bash
git clone https://github.com/l3ad3r1/agent-core.git
git clone https://github.com/l3ad3r1/Hermes-Agent-Android.git
cd Hermes-Agent-Android && ./gradlew :app:assembleDebug
```

Run the engine's own tests from either app checkout, or standalone:

```bash
./gradlew :core:llm:testDebugUnitTest :core:domain:test
```

## Consumer pinning (`agent-core.ref`)

Each app pins the engine commit it builds against in a top-level `agent-core.ref`.
That pin is **load-bearing, not documentation**: the apps' CI and release
workflows check this repository out at exactly that commit. Local builds do not —
they map `:core:*` straight onto your working tree — so a mismatch is invisible
until CI runs.

**A change to a shared API or JNI signature here, and the app-side change that
depends on it, must be repinned in the same change.**

Skipping it is quiet. Both apps shipped v1.0.2 with red CI for exactly this
reason: they had moved to a `ConversationCompressor.brief()` without its
`anchorId` parameter while still pinned to a commit that required it, so CI
compiled new app code against old engine code and failed on a signature nothing
locally disagreed with.

## Known limitations

- **The vector index is in-memory.** `InMemoryVectorStore` loses every vector on
  process death and rebuilds by re-embedding. A persistent backend behind the
  `VectorStore` interface is the single highest-value engine contribution.
- **The embedding model is not downloaded by the apps.** `MiniLmEmbeddingService`
  is real (ONNX Runtime, all-MiniLM-L6-v2 int8, 384-dim) and is what DI binds,
  but it reads `model.onnx` and `vocab.txt` from `AI Models/embeddings/all-MiniLM-L6-v2`
  on shared storage and silently falls back to `HashingEmbeddingService` when they
  are absent. Nothing in either app fetches them yet, so a fresh install gets
  hash vectors and no warning.
- **`GrpcPluginSandbox` is a stub.** Third-party plugins do not yet run in an
  isolated process.
- **Memory consolidation uses a regex extractor** rather than the model.

Issues are tracked on the [Hermes issue tracker](https://github.com/l3ad3r1/Hermes-Agent-Android/issues);
engine-level ones carry the `llm-backend` or `plugin` labels.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). In short: keep `core:domain` free of
Android imports, put infrastructure behind interfaces, preserve fail-closed
verification, add tests for persistence, cancellation, idempotence and failure
paths, and never commit signing credentials or local machine properties.

## Plugin modules

The apps expose **Settings → Features → Modules**. Public module publishing and
the catalog authoring guide live in the
[Hermes/Jeeves Modules repository](https://github.com/l3ad3r1/hermes-jeeves-modules);
the catalog, package, signing, trust and installer contract is in
[`docs/PLUGIN_REPOSITORY.md`](docs/PLUGIN_REPOSITORY.md).

## License

MIT — see [LICENSE](LICENSE).
