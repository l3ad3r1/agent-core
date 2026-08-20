# Hermes plugin repository contract

This document defines catalog schema version 1 for modules that Hermes and Jeeves can
discover from the same public repository. It is a package and trust contract, not yet a
complete downloader or Android service transport.

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
the embedded manifest, package name, version, size, APK digest, and signing certificate
against the catalog. Approval is then bound to the exact digest, signer, version, and
permission list. A changed package or permission request requires a new review.

The catalog codec and fail-closed verifier live in `:core:plugin`; shared data contracts
live in `:core:domain`. See [`samples/plugin-catalog-v1.json`](../samples/plugin-catalog-v1.json)
for a minimal document.

## Still required

- authenticated catalog fetching and artifact downloading;
- Android APK inspection and package-installer handoff;
- persistent trusted-publisher and approval storage;
- the exported Android service contract and concrete gRPC transport;
- permission-review and install UI in both products.

Until these layers exist, a catalog can be parsed and verified in tests but modules
cannot yet be installed or executed from the network.
