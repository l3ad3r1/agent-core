package com.hermes.agent.data.tools

import com.hermes.agent.data.tools.hub.SkillsHubClient
import com.hermes.agent.domain.repository.SkillRepository
import com.hermes.agent.domain.skill.HubSkillBundle
import com.hermes.agent.domain.skill.HubSkillMeta
import com.hermes.agent.domain.skill.SkillLintResult
import com.hermes.agent.domain.skill.SkillParsedMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillHubToolTest {

    private val hubClient = mockk<SkillsHubClient>()
    private val repo = mockk<SkillRepository>(relaxed = true)
    private val tool = SkillHubTool(hubClient, repo)

    private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
        pairs.associate { it.first to JsonPrimitive(it.second) }

    @Test
    fun `search returns matching hub skills`() = runTest {
        coEvery { hubClient.searchSkills("git") } returns listOf(
            HubSkillMeta(
                name = "git-workflow",
                description = "Automates git branches and commits",
                source = "github",
                identifier = "NousResearch/hermes-agent-skills/skills/git-workflow",
                repo = "NousResearch/hermes-agent-skills",
                path = "skills/git-workflow",
                tags = listOf("git", "vcs"),
            )
        )

        val result = tool.execute(args("action" to "search", "query" to "git"))
        assertTrue(result.success)
        assertTrue(result.output.orEmpty().contains("git-workflow"))
    }

    @Test
    fun `install saves skill with commit provenance`() = runTest {
        val meta = HubSkillMeta(
            name = "research-assistant",
            description = "Performs deep web research",
            identifier = "NousResearch/hermes-agent-skills/skills/research",
            repo = "NousResearch/hermes-agent-skills",
            path = "skills/research",
        )
        val lint = SkillLintResult(
            isValid = true,
            parsedMetadata = SkillParsedMetadata(
                name = "research-assistant",
                description = "Performs deep web research",
                version = "1.0.0",
                tags = listOf("research"),
            )
        )
        val bundle = HubSkillBundle(
            meta = meta,
            skillMarkdown = "---\nname: research-assistant\ndescription: Performs deep web research\n---\n# Instructions",
            commitSha = "abc1234def5678",
            lintResult = lint,
        )

        coEvery { hubClient.fetchSkillBundle("NousResearch/hermes-agent-skills/skills/research") } returns bundle

        val result = tool.execute(args("action" to "install", "identifier" to "NousResearch/hermes-agent-skills/skills/research"))
        assertTrue(result.success)
        assertTrue(result.output.orEmpty().contains("abc1234def5678"))
        coVerify { repo.saveSkill(match { it.pinnedCommit == "abc1234def5678" && it.lintStatus == "PASS" }) }
    }
}
