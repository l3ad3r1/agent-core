package com.hermes.agent.data.tools

import com.hermes.agent.data.tools.hub.SkillsHubClient
import com.hermes.agent.domain.model.Skill
import com.hermes.agent.domain.model.SkillLifecycle
import com.hermes.agent.domain.repository.SkillRepository
import com.hermes.agent.domain.skill.SkillTap
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool for searching, inspecting, and installing skills from GitHub Skills Hub with
 * automated linting and commit SHA provenance pinning.
 * Ported from upstream `skills_hub.py` and `skill_sync.py`.
 */
@Singleton
class SkillHubTool @Inject constructor(
    private val hubClient: SkillsHubClient,
    private val skillRepository: SkillRepository,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "skills_hub",
        description = "Discover, inspect, and install curated skills from the community Skills Hub (NousResearch, OpenAI, Anthropic). " +
            "Actions: 'search' (find skills by keyword), 'inspect' (preview a skill's metadata and instructions), " +
            "'install' (download, lint, and install a skill with verified Git commit provenance), 'list_taps' (show configured repositories).",
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "The operation to perform: 'search', 'inspect', 'install', or 'list_taps'.",
                required = true,
                enumValues = listOf("search", "inspect", "install", "list_taps"),
            ),
            ToolParameter(
                name = "query",
                type = ToolParameterType.STRING,
                description = "Search query when action='search'.",
                required = false,
            ),
            ToolParameter(
                name = "identifier",
                type = ToolParameterType.STRING,
                description = "The full skill identifier (e.g. 'NousResearch/hermes-agent-skills/skills/research') for 'inspect' or 'install'.",
                required = false,
            ),
            ToolParameter(
                name = "category",
                type = ToolParameterType.STRING,
                description = "Category to assign to the skill when installed (default: 'hub').",
                required = false,
            ),
        ),
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val action = (arguments["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.error("Missing required parameter 'action'")

        return when (action) {
            "search" -> {
                val query = (arguments["query"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                handleSearch(query)
            }
            "inspect" -> {
                val identifier = (arguments["identifier"] as? JsonPrimitive)?.contentOrNull?.trim()
                    ?: return ToolResult.error("action='inspect' requires 'identifier'")
                handleInspect(identifier)
            }
            "install" -> {
                val identifier = (arguments["identifier"] as? JsonPrimitive)?.contentOrNull?.trim()
                    ?: return ToolResult.error("action='install' requires 'identifier'")
                val category = (arguments["category"] as? JsonPrimitive)?.contentOrNull?.trim() ?: "hub"
                handleInstall(identifier, category)
            }
            "list_taps" -> {
                handleListTaps()
            }
            else -> ToolResult.error("Unsupported action '$action'")
        }
    }

    private suspend fun handleSearch(query: String): ToolResult {
        val results = hubClient.searchSkills(query)
        if (results.isEmpty()) {
            return ToolResult.ok("No skills found matching '$query'. Try another search term or check configured taps.")
        }
        val output = buildString {
            appendLine("Found ${results.size} skills on Skills Hub:")
            for (item in results) {
                appendLine("- **${item.name}** (`${item.identifier}`)")
                appendLine("  ${item.description}")
                if (item.tags.isNotEmpty()) {
                    appendLine("  Tags: ${item.tags.joinToString(", ")}")
                }
            }
            appendLine("\nTo inspect: `skills_hub(action=\"inspect\", identifier=\"<id>\")`")
            appendLine("To install: `skills_hub(action=\"install\", identifier=\"<id>\")`")
        }
        return ToolResult.ok(output.trim())
    }

    private suspend fun handleInspect(identifier: String): ToolResult {
        val bundle = hubClient.fetchSkillBundle(identifier)
            ?: return ToolResult.error("Could not fetch skill '$identifier' from GitHub.")

        val output = buildString {
            appendLine("# Skill: ${bundle.meta.name}")
            appendLine("- **Identifier:** `${bundle.meta.identifier}`")
            appendLine("- **Repository:** `${bundle.meta.repo}`")
            appendLine("- **Pinned Commit:** `${bundle.commitSha}`")
            appendLine("- **Lint Status:** ${if (bundle.lintResult.isValid) "✅ PASSED" else "❌ FAILED"}")
            if (bundle.lintResult.warnings.isNotEmpty()) {
                appendLine("- **Warnings:** ${bundle.lintResult.warnings.joinToString("; ")}")
            }
            appendLine("\n### Description")
            appendLine(bundle.meta.description)
            appendLine("\n### Instructions Preview")
            val preview = bundle.skillMarkdown.take(800)
            appendLine(preview)
            if (bundle.skillMarkdown.length > 800) appendLine("... (truncated)")
        }
        return ToolResult.ok(output.trim())
    }

    private suspend fun handleInstall(identifier: String, category: String): ToolResult {
        val bundle = hubClient.fetchSkillBundle(identifier)
            ?: return ToolResult.error("Could not fetch skill '$identifier' for installation.")

        if (!bundle.lintResult.isValid) {
            val errs = bundle.lintResult.errors.joinToString("\n- ")
            return ToolResult.error("Skill '$identifier' failed linter validation:\n- $errs")
        }

        val meta = bundle.lintResult.parsedMetadata
        val now = System.currentTimeMillis()

        val skill = Skill(
            id = "hub_" + UUID.randomUUID().toString().take(8),
            name = meta?.name ?: bundle.meta.name,
            description = meta?.description ?: bundle.meta.description,
            version = meta?.version ?: "1.0.0",
            content = bundle.skillMarkdown,
            category = category,
            tags = (meta?.tags ?: emptyList()) + listOf("hub", "installed"),
            isBuiltIn = false,
            createdAt = now,
            updatedAt = now,
            requiresTools = meta?.requiresTools ?: emptyList(),
            fallbackForTools = meta?.fallbackForTools ?: emptyList(),
            lifecycleState = SkillLifecycle.ACTIVE,
            pinned = true,
            sourceUrl = bundle.meta.downloadUrl,
            pinnedCommit = bundle.commitSha,
            installedAt = now,
            lintStatus = "PASS",
        )

        skillRepository.saveSkill(skill)
        Timber.tag("SkillHubTool").i("Installed skill %s pinned to commit %s", skill.name, bundle.commitSha)

        val output = buildString {
            appendLine("✅ Successfully installed skill **${skill.name}** from Skills Hub!")
            appendLine("- **Identifier:** `${bundle.meta.identifier}`")
            appendLine("- **Pinned Commit:** `${bundle.commitSha}`")
            appendLine("- **Category:** `${skill.category}`")
            appendLine("- **Lint Status:** PASS")
            appendLine("\nThe skill is now active and will be automatically referenced when applicable.")
        }
        return ToolResult.ok(output.trim())
    }

    private fun handleListTaps(): ToolResult {
        val taps = SkillTap.DEFAULT_TAPS
        val output = buildString {
            appendLine("Configured Skills Hub Taps:")
            for (tap in taps) {
                appendLine("- **${tap.repo}** (path: `${tap.path}`, branch: `${tap.branch}`)")
            }
        }
        return ToolResult.ok(output.trim())
    }
}
