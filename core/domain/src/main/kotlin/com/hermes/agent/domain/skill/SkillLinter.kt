package com.hermes.agent.domain.skill

/**
 * Result of linting a skill definition.
 */
data class SkillLintResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val parsedMetadata: SkillParsedMetadata? = null,
)

data class SkillParsedMetadata(
    val name: String,
    val description: String,
    val version: String = "1.0.0",
    val category: String = "general",
    val tags: List<String> = emptyList(),
    val requiresTools: List<String> = emptyList(),
    val fallbackForTools: List<String> = emptyList(),
    val author: String? = null,
    val body: String = "",
)

/**
 * Validates skill markdown files against the Hermes skill schema.
 * Ported from upstream `skill_linter.py`.
 */
object SkillLinter {

    private val FRONTMATTER_REGEX = Regex("""^---\s*\r?\n(.*?)\r?\n---\s*(?:\r?\n(.*))?$""", RegexOption.DOT_MATCHES_ALL)
    private val NAME_REGEX = Regex("""^[a-zA-Z0-9_-]{2,64}$""")
    private val DANGEROUS_PATTERNS = listOf(
        Regex("""(?i)\brm\s+-rf\s+[/~]"""),
        Regex("""(?i)\bmkfs\b"""),
        Regex("""(?i)\bdd\s+if=.*of=/dev/"""),
        Regex("""(?i):\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:"""), // fork bomb
    )

    fun lint(markdownContent: String): SkillLintResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (markdownContent.isBlank()) {
            return SkillLintResult(
                isValid = false,
                errors = listOf("Skill content is empty."),
            )
        }

        val match = FRONTMATTER_REGEX.find(markdownContent.trim())
        if (match == null) {
            return SkillLintResult(
                isValid = false,
                errors = listOf("Missing or malformed YAML frontmatter (must start with '---' and close with '---')."),
            )
        }

        val frontmatterRaw = match.groupValues[1]
        val bodyRaw = if (match.groupValues.size > 2) match.groupValues[2] else ""

        val rawMap = parseYamlFlat(frontmatterRaw)

        val name = rawMap["name"]?.trim()
        if (name.isNullOrBlank()) {
            errors.add("Frontmatter missing required 'name' field.")
        } else if (!NAME_REGEX.matches(name)) {
            errors.add("Skill name '$name' is invalid. Must be 2-64 characters matching [a-zA-Z0-9_-].")
        }

        val description = rawMap["description"]?.trim()
        if (description.isNullOrBlank()) {
            errors.add("Frontmatter missing required 'description' field.")
        } else if (description.length < 5) {
            warnings.add("Skill description is very short (${description.length} chars). A descriptive explanation is recommended.")
        }

        val version = rawMap["version"]?.trim() ?: "1.0.0"
        val category = rawMap["category"]?.trim() ?: "general"
        val author = rawMap["author"]?.trim()

        val tags = parseList(rawMap["tags"])
        val requiresTools = parseList(rawMap["requires_tools"] ?: rawMap["requiresTools"])
        val fallbackForTools = parseList(rawMap["fallback_for_tools"] ?: rawMap["fallbackForTools"])

        if (bodyRaw.trim().isEmpty()) {
            warnings.add("Skill instruction body is empty.")
        }

        for (pattern in DANGEROUS_PATTERNS) {
            if (pattern.containsMatchIn(bodyRaw)) {
                warnings.add("Potential destructive command pattern detected in skill body: ${pattern.pattern}")
            }
        }

        val isValid = errors.isEmpty()
        val metadata = if (isValid) {
            SkillParsedMetadata(
                name = name ?: "unnamed",
                description = description ?: "",
                version = version,
                category = category,
                tags = tags,
                requiresTools = requiresTools,
                fallbackForTools = fallbackForTools,
                author = author,
                body = bodyRaw.trim(),
            )
        } else null

        return SkillLintResult(
            isValid = isValid,
            errors = errors,
            warnings = warnings,
            parsedMetadata = metadata,
        )
    }

    private fun parseYamlFlat(yaml: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val lines = yaml.lines()
        var currentKey: String? = null
        var currentVal = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val colonIdx = line.indexOf(':')
            if (colonIdx > 0 && !line.startsWith(" ") && !line.startsWith("\t")) {
                if (currentKey != null) {
                    map[currentKey] = currentVal.toString().trim()
                }
                currentKey = line.substring(0, colonIdx).trim()
                currentVal = StringBuilder(line.substring(colonIdx + 1).trim())
            } else if (currentKey != null) {
                currentVal.append("\n").append(trimmed)
            }
        }
        if (currentKey != null) {
            map[currentKey] = currentVal.toString().trim()
        }
        return map
    }

    private fun parseList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val cleaned = raw.trim()
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            return cleaned.substring(1, cleaned.length - 1)
                .split(",")
                .map { it.trim().trim('"', '\'') }
                .filter { it.isNotEmpty() }
        }
        // Multi-line list (e.g. - tag1 \n - tag2)
        return cleaned.lines()
            .map { it.trim().removePrefix("-").trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }
    }
}
