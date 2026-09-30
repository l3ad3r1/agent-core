package com.hermes.agent.data.plugin.evolution

import com.hermes.agent.data.plugin.script.ModuleHosts
import com.hermes.agent.data.plugin.script.ScriptPluginEngine
import com.hermes.agent.data.plugin.script.ScriptPluginHost
import com.hermes.agent.data.plugin.script.ScriptPluginManifest
import java.util.Collections

/**
 * Runs a module's own smoke tests on the phone, in a scratch [ScriptPluginEngine]
 * that is never the live one: nothing it loads is visible to the agent, and its
 * host has no network, no user data, and nothing to write to.
 *
 * The same Rhino sandbox rules apply as in production (no Java, instruction
 * budget, deadline), so a module that passes here loads the same way live.
 */
class ModuleSmokeTestRunner(
    private val engineFactory: () -> ScriptPluginEngine = { ScriptPluginEngine() },
) {

    data class Result(val test: ModuleSmokeTest, val passed: Boolean, val output: String, val error: String?)

    data class Report(val passed: Boolean, val results: List<Result>, val problems: List<String>) {
        /** Human-readable, for the review screen and for the bots. */
        fun summary(): String = buildString {
            append(if (passed) "PASSED" else "FAILED")
            append(" — ${results.count { it.passed }}/${results.size} smoke tests passed")
            problems.forEach { append("\n- ").append(it) }
            results.forEach { r ->
                append("\n").append(if (r.passed) "✓ " else "✗ ").append(r.test.tool)
                append(" ").append(r.test.arguments.toString().take(120))
                if (!r.passed) {
                    append(" → expected output containing \"").append(r.test.expectContains.take(80)).append('"')
                    append(", got ").append(r.error?.let { "error: ${it.take(160)}" } ?: "\"${r.output.take(160)}\"")
                }
            }
        }
    }

    /**
     * A host that answers every gated call with an empty, deterministic value and
     * records what was attempted, so tests never depend on the network or the
     * user's data and a disallowed host is still caught.
     */
    private class SandboxHost(private val hosts: List<String>) : ScriptPluginHost {
        val violations: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun log(pluginId: String, message: String) = Unit
        override fun readData(pluginId: String, collection: String, query: String): String = "[]"
        override fun writeData(pluginId: String, collection: String, payload: String): String =
            """{"ok":false,"sandbox":true}"""

        override fun httpGet(pluginId: String, url: String, allowedHosts: List<String>): String {
            val host = runCatching { java.net.URI(url).host }.getOrNull()
            if (host == null || !url.startsWith("https://")) {
                violations += "requested a non-HTTPS or malformed URL"
            } else if (!ModuleHosts.matches(host, hosts)) {
                violations += "requested $host, which is not in its hosts list"
            }
            return ""
        }
    }

    suspend fun run(
        manifest: ScriptPluginManifest,
        tests: List<ModuleSmokeTest>,
        grantedPermissions: Set<String>,
    ): Report {
        val host = SandboxHost(manifest.hosts)
        val engine = engineFactory().also { it.host = host }
        val problems = mutableListOf<String>()
        try {
            val loadFailures = engine.reload(
                listOf(ScriptPluginEngine.PluginSpec(manifest.id, manifest.main, grantedPermissions, manifest.hosts)),
            )
            if (loadFailures.isNotEmpty()) {
                problems += loadFailures.map { "Load failed: ${it.take(200)}" }
                return Report(false, emptyList(), problems)
            }
            val registered = engine.registeredToolNames(manifest.id).toSet()
            manifest.tools.map { it.name }.filter { it !in registered }.forEach {
                problems += "Tool '$it' is declared but the script never registered it"
            }
            val results = tests.map { test ->
                engine.execute(manifest.id, test.tool, test.arguments).fold(
                    onSuccess = { out -> Result(test, out.contains(test.expectContains), out, null) },
                    onFailure = { err -> Result(test, false, "", err.message ?: "failed") },
                )
            }
            host.violations.distinct().forEach { problems += "Network policy: $it" }
            val passed = problems.isEmpty() && results.isNotEmpty() && results.all { it.passed }
            return Report(passed, results, problems)
        } finally {
            // Drop the scratch scopes; nothing from the test run is kept.
            runCatching { engine.reload(emptyList()) }
        }
    }
}
