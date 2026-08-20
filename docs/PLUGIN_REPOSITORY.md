# Hermes plugin repository contract

This document defines catalog schema version 1 for modules that Hermes and Jeeves can
discover from the same public repository. It is a package, delivery, and trust contract,
but not yet an Android package-installer or service transport.

## Repository layout

Publish one `catalog-v1.json` document and immutable APK artifacts. Artifact URLs may
point at GitHub Releases or any other HTTPS host; clients do not depend on a particular
repository provider.

```text
catalog-v1.json
artifacts/
  com.example.weather/
    7/
      plugin.apk
```

Each catalog entry contains:

- the complete plugin manifest and tool schemas shown to the user;
- the immutable APK SHA-256 and byte size;
- the Android package and exported service class used by future service discovery;
- the APK signing-certificate SHA-256;
- the minimum host version and plugin protocol version.

Plugin IDs equal Android package names in schema v1. Catalogs must use HTTPS artifact
URLs, unique plugin IDs and tool names, positive version numbers, and protocol version 1.

## Trust and approval

HTTPS and an APK checksum protect transport integrity but do not establish publisher
trust. A catalog is allowed to declare a signer only as an expectation. The host accepts
that signer when either:

1. its certificate fingerprint is already in the host's trusted-publisher store; or
2. the user explicitly trusts the publisher while approving the installation.

Before approval, the host extracts package facts from the downloaded APK and compares
the embedded manifest, package name, version, size, APK digest, signing certificate,
and exported plugin service against the catalog. Approval is then bound to the exact
digest, signer, version, and permission list. A changed package or permission request
requires a new review.

Android plugin APKs embed the schema-v1 manifest as a JSON string in application
metadata named `com.hermes.agent.PLUGIN_MANIFEST_V1`. The service named by the catalog
must be declared exported in the APK. The inspector rejects non-APK files, packages
Android cannot parse, missing metadata, invalid manifests, unsupported version codes,
and packages that do not have exactly one current signer.

The catalog codec and fail-closed verifier live in `:core:plugin`; shared data contracts
live in `:core:domain`. See [`samples/plugin-catalog-v1.json`](../samples/plugin-catalog-v1.json)
for a minimal document.

## Catalog and artifact delivery

Shared core fetches catalogs only over HTTPS, caps catalog responses at 1 MiB and
declared APK artifacts at 256 MiB, and runs the strict codec before returning any entry.
A caller may provide a request authorizer for a public-repository API token without
transferring credential ownership to shared core. Catalog and artifact authorization
are requested separately, so credentials do not have to be shared across different
hosts.

APK downloads are streamed into a temporary file inside a caller-owned private
directory. The downloader requires the exact catalog byte count and SHA-256 before it
renames the staged file to an immutable, digest-qualified `.apk` name. Failed, partial,
oversized, or digest-mismatched transfers leave no promoted package. A previously
downloaded immutable package is reused only after its size and digest are checked again.

## Android installer handoff and durable decisions

Shared core exposes the app-private plugin download directory and the Android package
installer boundary. Before opening the system installer it binds the downloaded
artifact, verified package evidence, and user authorization to the same plugin ID,
version, APK digest, signer, and catalog entry. It then inspects the APK again to reject
a file changed after approval and refuses files outside the private plugin directory.

If Android has not granted the host permission to request package installation, the
handoff returns `PermissionRequired`; the host can explicitly open Android's per-app
unknown-sources settings. Platform launch and settings failures are returned to the
caller so install UI can present an actionable error instead of failing silently.

Each host must declare `REQUEST_INSTALL_PACKAGES`, provide a non-exported `FileProvider`
at `${applicationId}.fileprovider`, and expose `<files-path name="plugins"
path="plugins/" />`. Hermes and Jeeves both carry this host-only manifest resource;
the installer implementation and private directory contract remain shared.

The shared plugin layer also persists publisher trust and approved install snapshots in
app-private, versioned storage. Trust is keyed by plugin ID plus normalized signer
certificate SHA-256 and can be revoked. An approval snapshot includes the complete
review request (including permissions, digest, version, signer, and trust state), so a
restart or a changed artifact cannot reuse an earlier approval. Writes are replace-based
and bounded; storage or decoding failures return an error and never grant trust.

The UI-neutral review coordinator is the only application-layer path that may advance a
review: it refreshes durable publisher trust, validates the decision, persists the exact
post-trust snapshot, and requires that snapshot to still exist before installer handoff.
Hermes and Jeeves can render different screens, but they share this sequencing and its
fail-closed behavior.

After a successful installer launch, the coordinator records the exact package as
`HANDED_OFF`. A shared manifest receiver consumes Android `PACKAGE_ADDED` and
`PACKAGE_REPLACED` events off the main thread and changes only the matching pending
package to `INSTALLED`. Completion records are replace-based and restart-safe, so
duplicate broadcasts do not create duplicate history and unrelated package installs
cannot mark a plugin complete.

## Current host integration

Hermes and Jeeves expose the catalog downloader under **Settings → Features → Modules**.
The screen accepts a catalog URL, displays validated entries, and downloads an immutable
APK into the host's private plugin directory. Both products consume the same
`PluginModuleDownloadCoordinator`, catalog codec, verifier, and artifact downloader.

The download screen intentionally stops at verified staging; installer approval and
Android package handoff remain separate security-gated steps.

## Still required

- the exported Android service contract and concrete gRPC transport;
- a complete permission-review and install UI in both products.

Until these layers are completed, downloaded APKs are staged and verified but are not
automatically activated as installed modules.
