package com.hermes.agent.data.security

import com.hermes.agent.domain.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Output redaction — ported from hermes-agent's in-process security
 * heuristics (SECURITY.md "In-Process Heuristics": approval gate,
 * redaction, Skills Guard).
 *
 * Scrubs secrets from tool outputs before they are fed back into the LLM
 * conversation (and therefore before they can be echoed to the UI, stored
 * in history, or leaked to a connected messaging platform via `notify`).
 *
 * Two layers:
 *  1. **Known secrets** — the user's configured credentials (cloud/aux API
 *     keys, GitHub PAT) are replaced wherever they appear verbatim.
 *  2. **Pattern heuristics** — common credential shapes (OpenAI `sk-`,
 *     GitHub `ghp_`/`github_pat_`, Slack `xox*-`, Google `AIza`, NVIDIA
 *     `nvapi-`, AWS `AKIA`, bearer tokens) are masked even when they are
 *     not the user's own configured keys — e.g. a key accidentally read
 *     from a file or web page by a tool.
 */
@Singleton
class OutputRedactor @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {

    suspend fun redact(text: String): String {
        if (text.isBlank()) return text
        var out = text

        // Layer 1: literal configured secrets.
        // Without configured credentials, heuristic-only redaction can leak arbitrary secrets.
        // Callers must fail closed if settings cannot be read; cancellation also propagates.
        val settings = settingsRepository.current()
        run {
            val configuredSecrets = mutableListOf<Pair<String, String>>()
            if (settings.cloudApiKey.isNotBlank()) configuredSecrets += settings.cloudApiKey to "cloud-api-key"
            if (settings.auxApiKey.isNotBlank()) configuredSecrets += settings.auxApiKey to "aux-api-key"
            if (settings.apiServerKey.isNotBlank()) configuredSecrets += settings.apiServerKey to "api-server-key"
            if (settings.sshPassword.isNotBlank()) configuredSecrets += settings.sshPassword to "ssh-password"
            if (settings.telegramBotToken.isNotBlank()) configuredSecrets += settings.telegramBotToken to "telegram-bot-token"
            if (settings.homeAssistantToken.isNotBlank()) configuredSecrets += settings.homeAssistantToken to "home-assistant-token"
            if (settings.remoteGatewayApiKey.isNotBlank()) configuredSecrets += settings.remoteGatewayApiKey to "remote-gateway-api-key"
            if (settings.backupPassphrase.isNotBlank()) configuredSecrets += settings.backupPassphrase to "backup-passphrase"
            settings.cloudProviderProfiles.forEach { profile ->
                if (profile.apiKey.isNotBlank()) {
                    configuredSecrets += profile.apiKey to "${profile.name.lowercase().replace(" ", "-")}-api-key"
                }
            }
            configuredSecrets.forEach { (secret, label) ->
                if (secret.length >= MIN_SECRET_LENGTH && out.contains(secret)) {
                    out = out.replace(secret, "[redacted:$label]")
                }
            }
        }

        // Layer 2: credential-shaped patterns.
        out = maskShapes(out)
        return out
    }

    companion object {
        /** Secrets shorter than this are too likely to collide with normal
         *  text (e.g. a 4-char key would redact random substrings). */
        private const val MIN_SECRET_LENGTH = 8

        /**
         * Pattern layer, usable without settings (and from tests). Order matters: whole private-key
         * blocks first, then shapes where only the secret part is masked (connection-string
         * passwords, `key = value` assignments), then bare token shapes.
         */
        fun maskShapes(text: String): String {
            if (text.isBlank()) return text
            var out = PRIVATE_KEY_BLOCK.replace(text) { "[redacted:private-key]" }
            // scheme://user:PASSWORD@host — keep scheme, user and host so the line still reads.
            out = CONNECTION_PASSWORD.replace(out) { m -> "${m.groupValues[1]}[redacted:password]@" }
            // password=..., "api_key": "...", client_secret: '...' — keep the name, mask the value.
            out = SECRET_ASSIGNMENT.replace(out) { m -> "${m.groupValues[1]}[redacted:secret]" }
            for ((pattern, label) in TOKEN_SHAPES) {
                out = pattern.replace(out) { "[redacted:$label]" }
            }
            return out
        }

        /** PEM / OpenSSH private keys of any algorithm (RSA, EC, DSA, ENCRYPTED, OPENSSH...). */
        private val PRIVATE_KEY_BLOCK = Regex(
            """-----BEGIN ([A-Z0-9]+ )*PRIVATE KEY-----[\s\S]*?-----END ([A-Z0-9]+ )*PRIVATE KEY-----""",
        )

        /** `postgres://app:hunter22@db`, `mongodb+srv://u:p@cluster`, `redis://:pw@host`. */
        private val CONNECTION_PASSWORD = Regex("""\b([a-z][a-z0-9+.-]{1,20}://[^\s:/@]*:)([^\s@/]{3,})@""")

        /**
         * A credential-named key followed by `=` or `:` and a value: group 1 is the name and
         * separator (kept), group 2 the value (masked). Bare `token=` is left alone (too common in
         * ordinary text and URLs), and placeholders ($VAR, ***, <key>, {...}) are not secrets.
         */
        private val SECRET_ASSIGNMENT = Regex(
            """(?i)(["']?\b(?:password|passwd|pwd|passphrase|secret|client[_-]?secret|api[_-]?key|apikey|""" +
                """access[_-]?token|auth[_-]?token|refresh[_-]?token|private[_-]?key|secret[_-]?key)\b["']?""" +
                """\s*[:=]\s*["']?)(?![${'$'}*<{\[\s"'])([^\s"',;]{6,})""",
        )

        private val TOKEN_SHAPES: List<Pair<Regex, String>> = listOf(
            Regex("""\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}""") to "jwt",
            Regex("""sk-[A-Za-z0-9_-]{16,}""") to "api-key",
            Regex("""\b(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{20,}""") to "stripe-key",
            Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}""") to "github-token",
            Regex("""github_pat_[A-Za-z0-9_]{20,}""") to "github-token",
            Regex("""\bglpat-[A-Za-z0-9_-]{20,}""") to "gitlab-token",
            Regex("""xox[bapr]-[A-Za-z0-9-]{10,}""") to "slack-token",
            Regex("""AIza[A-Za-z0-9_-]{30,}""") to "google-api-key",
            Regex("""nvapi-[A-Za-z0-9_-]{20,}""") to "nvidia-api-key",
            Regex("""\bhf_[A-Za-z0-9]{30,}""") to "huggingface-token",
            Regex("""\bgsk_[A-Za-z0-9]{40,}""") to "groq-api-key",
            Regex("""\bxai-[A-Za-z0-9]{40,}""") to "xai-api-key",
            Regex("""\b\d{8,10}:AA[A-Za-z0-9_-]{33}\b""") to "telegram-bot-token",
            Regex("""AKIA[A-Z0-9]{16}""") to "aws-access-key",
            Regex("""(?i)\bAccountKey=[A-Za-z0-9+/=]{40,}""") to "azure-storage-key",
            Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]{20,}""") to "bearer-token",
        )
    }
}
