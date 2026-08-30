# Phase 4 — Transport security hardening

Tracks work referenced from `core/settings/.../security/CertificatePinningConfig.kt`.

## Status

Certificate pinning is currently **disabled** (`CertificatePinningConfig.pinner` builds an
empty `CertificatePinner`). TLS still validates against the system trust store — this is a
safe no-op, not a broken pin list — but no extra protection against a compromised/forced CA
is in place yet for the built-in cloud LLM endpoints.

## Capturing real certificate hashes

To re-enable pinning for a given host:

1. Capture the leaf certificate's SHA-256 pin from a live handshake, e.g.:
   ```bash
   openssl s_client -connect api.openai.com:443 -servername api.openai.com < /dev/null 2>/dev/null \
     | openssl x509 -pubkey -noout \
     | openssl pkey -pubin -outform der \
     | openssl dgst -sha256 -binary \
     | openssl enc -base64
   ```
2. Also capture the issuing intermediate's pin the same way, so a leaf rotation within the
   same CA chain doesn't lock users out before the next app update ships the new pin.
3. Add both as `.add(host, "sha256/<hash>=")` lines in `CertificatePinningConfig.pinner`.
4. Keep the previous cert's pin alongside the new one for one rotation cycle (annual, for
   OpenAI/Anthropic-style providers) so an in-flight rotation doesn't hard-fail requests.

## Rotation policy

See the class doc comment in `CertificatePinningConfig.kt` — pins are additive during a
rotation window, not swapped in place. A future phase may move the pin list to a
remote-config endpoint so rotation doesn't require an app update; not started.
