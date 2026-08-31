package com.hermes.agent.domain.skill

import kotlinx.serialization.Serializable

/**
 * Metadata for a skill indexed or discovered on the Skills Hub.
 */
@Serializable
data class HubSkillMeta(
    val name: String,
    val description: String,
    val source: String = "github",
    val identifier: String, // e.g. "NousResearch/hermes-agent-skills/skills/research"
    val repo: String,       // e.g. "NousResearch/hermes-agent-skills"
    val path: String,       // e.g. "skills/research"
    val branch: String = "main",
    val commitSha: String? = null,
    val trustLevel: String = "trusted", // "official" | "trusted" | "community"
    val tags: List<String> = emptyList(),
    val downloadUrl: String? = null,
)

/**
 * A skill tap pointing to a GitHub repo holding curated skills.
 */
@Serializable
data class SkillTap(
    val repo: String,
    val path: String = "skills",
    val branch: String = "main",
    val isDefault: Boolean = false,
) {
    companion object {
        val DEFAULT_TAPS = listOf(
            SkillTap(repo = "NousResearch/hermes-agent-skills", path = "skills", isDefault = true),
            SkillTap(repo = "anthropics/skills", path = "skills", isDefault = true),
            SkillTap(repo = "openai/skills", path = "skills/.curated", isDefault = true),
        )
    }
}

/**
 * A full skill payload downloaded from the hub ready for linting and persistence.
 */
data class HubSkillBundle(
    val meta: HubSkillMeta,
    val skillMarkdown: String,
    val commitSha: String,
    val lintResult: SkillLintResult,
)
