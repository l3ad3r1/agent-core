package com.hermes.agent.data.notifications

import timber.log.Timber

/**
 * Result of screening a list of captured notifications.
 */
data class ScreenedNotificationsResult(
    val notifications: List<CapturedNotification>,
    val droppedCount: Int,
)

/**
 * Screening stage between [NotificationGateway] and tool / LLM consumers.
 *
 * Implements strict prompt injection defenses (L-009 boundary):
 * 1. Drops (does not merely redact) notifications matching injection patterns:
 *    - Role tags: system:, assistant:, <|...|>
 *    - Tool-call syntax: <tool_call, <?tool_call, {"name":, function_call
 *    - Imperative override phrases: ignore (all )?previous, disregard .* instructions, you are now, new instructions
 *    - Fenced code blocks: ```
 * 2. Hard-truncates: title <= 120 chars, text <= 500 chars, list <= requested limit (1..50).
 * 3. Excludes own-package notifications.
 * 4. Logs dropped items with package name ONLY (never the body).
 */
object NotificationContentScreen {

    private val INJECTION_PATTERNS = listOf(
        // Role tags
        Regex("""(?i)\b(system|assistant|user)\s*:"""),
        Regex("""<\|(?:im_start|im_end|.*?)\|>"""),
        // Tool-call syntax
        Regex("""(?i)<\??tool_call"""),
        Regex("""(?i)\{"name"\s*:"""),
        Regex("""(?i)\bfunction_call\b"""),
        // Imperative override phrases
        Regex("""(?i)\bignore\s+(?:all\s+)?previous\b"""),
        Regex("""(?i)\bdisregard\s+.*?\s*instructions\b"""),
        Regex("""(?i)\byou\s+are\s+now\b"""),
        Regex("""(?i)\bnew\s+instructions\b"""),
        // Fenced code blocks
        Regex("""```"""),
    )

    private const val MAX_TITLE_LENGTH = 120
    private const val MAX_TEXT_LENGTH = 500

    fun screen(
        notifications: List<CapturedNotification>,
        ownPackageName: String,
        limit: Int = 10,
    ): ScreenedNotificationsResult {
        val safeLimit = limit.coerceIn(1, 50)
        val candidateList = notifications.filter { it.packageName != ownPackageName }

        var droppedCount = 0
        val screened = mutableListOf<CapturedNotification>()

        for (notif in candidateList) {
            if (containsInjectionPattern(notif.title) || containsInjectionPattern(notif.text)) {
                Timber.tag("NotificationScreen").w(
                    "Dropped notification from package: %s (content flagged as unsafe)",
                    notif.packageName,
                )
                droppedCount++
                continue
            }

            val sanitizedTitle = notif.title.take(MAX_TITLE_LENGTH).trim()
            val sanitizedText = notif.text.take(MAX_TEXT_LENGTH).trim()

            screened.add(
                notif.copy(
                    title = sanitizedTitle,
                    text = sanitizedText,
                ),
            )
        }

        return ScreenedNotificationsResult(
            notifications = screened.takeLast(safeLimit),
            droppedCount = droppedCount,
        )
    }

    private fun containsInjectionPattern(content: String): Boolean {
        if (content.isBlank()) return false
        return INJECTION_PATTERNS.any { it.containsMatchIn(content) }
    }
}
