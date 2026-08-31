package com.hermes.agent.data.tools.hub

import com.hermes.agent.domain.skill.HubSkillBundle
import com.hermes.agent.domain.skill.HubSkillMeta
import com.hermes.agent.domain.skill.SkillLinter
import com.hermes.agent.domain.skill.SkillTap
import com.hermes.agent.util.DispatcherProvider
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillsHubClient @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val json: Json,
) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun searchSkills(
        query: String,
        taps: List<SkillTap> = SkillTap.DEFAULT_TAPS,
    ): List<HubSkillMeta> = withContext(dispatchers.io) {
        val results = mutableListOf<HubSkillMeta>()
        val lowerQuery = query.trim().lowercase()

        for (tap in taps) {
            try {
                val list = listSkillsInTap(tap)
                for (item in list) {
                    val searchable = "${item.name} ${item.description} ${item.tags.joinToString(" ")}".lowercase()
                    if (lowerQuery.isEmpty() || searchable.contains(lowerQuery)) {
                        results.add(item)
                    }
                }
            } catch (t: Throwable) {
                Timber.tag("SkillsHubClient").w(t, "Failed to list skills in tap %s", tap.repo)
            }
        }
        results.distinctBy { it.identifier }
    }

    suspend fun listSkillsInTap(tap: SkillTap): List<HubSkillMeta> = withContext(dispatchers.io) {
        val apiUrl = "https://api.github.com/repos/${tap.repo}/contents/${tap.path.trim('/')}?ref=${tap.branch}"
        val req = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "Hermes-SkillsHub/1.0")
            .build()

        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            Timber.tag("SkillsHubClient").w("GitHub API HTTP %d for %s", resp.code, apiUrl)
            return@withContext emptyList()
        }

        val body = resp.body?.string().orEmpty()
        if (body.isBlank()) return@withContext emptyList()

        val items = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return@withContext emptyList()
        val skills = mutableListOf<HubSkillMeta>()

        for (elem in items) {
            val obj = elem.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: continue
            val name = obj["name"]?.jsonPrimitive?.content ?: continue
            val path = obj["path"]?.jsonPrimitive?.content ?: continue

            if (type == "dir" && !name.startsWith(".") && !name.startsWith("_")) {
                val identifier = "${tap.repo}/$path"
                val downloadUrl = "https://raw.githubusercontent.com/${tap.repo}/${tap.branch}/$path/SKILL.md"
                skills.add(
                    HubSkillMeta(
                        name = name,
                        description = "Curated skill $name from ${tap.repo}",
                        source = "github",
                        identifier = identifier,
                        repo = tap.repo,
                        path = path,
                        branch = tap.branch,
                        downloadUrl = downloadUrl,
                    )
                )
            }
        }
        skills
    }

    suspend fun fetchSkillBundle(identifier: String): HubSkillBundle? = withContext(dispatchers.io) {
        val parts = identifier.split("/", limit = 3)
        if (parts.size < 3) return@withContext null

        val owner = parts[0]
        val repoName = parts[1]
        val repo = "$owner/$repoName"
        val path = parts[2].trimEnd('/')

        val commitSha = fetchLatestCommitSha(repo, "main") ?: "unpinned-head"
        val rawUrl = "https://raw.githubusercontent.com/$repo/$commitSha/$path/SKILL.md"

        val req = Request.Builder()
            .url(rawUrl)
            .header("User-Agent", "Hermes-SkillsHub/1.0")
            .build()

        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            Timber.tag("SkillsHubClient").w("Failed to fetch raw SKILL.md from %s (HTTP %d)", rawUrl, resp.code)
            return@withContext null
        }

        val markdown = resp.body?.string().orEmpty()
        if (markdown.isBlank()) return@withContext null

        val lint = SkillLinter.lint(markdown)
        val skillName = lint.parsedMetadata?.name ?: path.substringAfterLast('/')
        val description = lint.parsedMetadata?.description ?: "Skill from $repo"

        val meta = HubSkillMeta(
            name = skillName,
            description = description,
            source = "github",
            identifier = identifier,
            repo = repo,
            path = path,
            commitSha = commitSha,
            downloadUrl = rawUrl,
            tags = lint.parsedMetadata?.tags ?: emptyList(),
        )

        HubSkillBundle(
            meta = meta,
            skillMarkdown = markdown,
            commitSha = commitSha,
            lintResult = lint,
        )
    }

    private fun fetchLatestCommitSha(repo: String, branch: String): String? {
        val url = "https://api.github.com/repos/$repo/commits/$branch"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "Hermes-SkillsHub/1.0")
            .build()

        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val obj = json.parseToJsonElement(body).jsonObject
                obj["sha"]?.jsonPrimitive?.content
            }
        }.getOrNull()
    }
}
