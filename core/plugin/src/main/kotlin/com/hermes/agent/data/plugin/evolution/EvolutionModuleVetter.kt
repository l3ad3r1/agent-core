package com.hermes.agent.data.plugin.evolution

import com.hermes.agent.data.plugin.script.ModuleHosts
import com.hermes.agent.data.plugin.script.ScriptModuleDigest
import com.hermes.agent.data.plugin.script.ScriptPluginManifest
import com.hermes.agent.data.plugin.script.ScriptPluginPermissions
import com.hermes.agent.domain.skill.SkillGuard
import com.hermes.agent.domain.tool.ToolDescriptor
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The phone's own static check of a bot-built module — authoritative regardless
 * of what the reviewer bot said. Everything here is deterministic and runs on the
 * exact bytes that will be installed.
 *
 * Rejects rather than repairs: every problem becomes a finding the builder gets
 * back, so what the user finally approves is exactly what the bots produced.
 */
class EvolutionModuleVetter(
    /** Looks up the tool currently registered under a name — the built-in for an override. */
    private val existingTool: (String) -> ToolDescriptor?,
    /** The module id that owns a registered tool, or null for a first-party tool. */
    private val moduleOwning: (String) -> String? = { null },
) {

    data class Report(
        val ok: Boolean,
        val findings: List<String>,
        val manifest: ScriptPluginManifest?,
        val tests: List<ModuleSmokeTest>,
        val sha256: String,
    )

    fun vet(manifestJson: String, kind: ProposalKind, targetTool: String?): Report {
        val findings = mutableListOf<String>()
        val sha = ScriptModuleDigest.sha256Hex(manifestJson)
        fun fail(): Report = Report(false, findings.distinct(), null, emptyList(), sha)

        if (!kind.hotLoadable) {
            findings += "An app change cannot ship as a module"
            return fail()
        }
        if (manifestJson.length > MAX_MANIFEST_CHARS) {
            findings += "Manifest is ${manifestJson.length} characters; the limit is $MAX_MANIFEST_CHARS"
            return fail()
        }
        val raw = runCatching { STRICT.parseToJsonElement(manifestJson).jsonObject }.getOrNull()
        if (raw == null) {
            findings += "Manifest is not a JSON object"
            return fail()
        }
        // The JSON tree keeps the last of two equal keys; a reviewer reading the raw text
        // may stop at the first. Code the reviewers did not see must not be what runs.
        duplicateKey(manifestJson)?.let {
            findings += "Manifest repeats the key \"${it.take(40)}\"; every key may appear once"
            return fail()
        }
        (raw.keys - ALLOWED_KEYS).takeIf { it.isNotEmpty() }?.let {
            findings += "Manifest has unexpected fields: ${it.sorted().joinToString()}"
        }
        val manifest = runCatching { ScriptPluginManifest.json.decodeFromJsonElement(ScriptPluginManifest.serializer(), raw) }
            .getOrElse {
                findings += "Manifest does not match the module schema: ${it.message?.take(200)}"
                return fail()
            }
        val tests = runCatching {
            ScriptPluginManifest.json.decodeFromJsonElement(ModuleSmokeTests.serializer(), raw).tests
        }.getOrElse {
            findings += "The tests array is malformed: ${it.message?.take(200)}"
            emptyList()
        }

        checkIdentity(manifest, findings)
        checkTools(manifest, kind, targetTool, findings)
        checkScript(manifest, findings)
        checkPermissions(manifest, findings)
        checkTests(manifest, tests, findings)

        val all = findings.distinct()
        return Report(all.isEmpty(), all, manifest.takeIf { all.isEmpty() }, tests, sha)
    }

    private fun checkIdentity(m: ScriptPluginManifest, findings: MutableList<String>) {
        if (!MODULE_ID.matches(m.id)) findings += "Module id must look like 'evo-some-name' (lowercase, 5–52 chars)"
        if (!SEMVER.matches(m.version)) findings += "Version must be MAJOR.MINOR.PATCH"
        if (m.name.isBlank() || m.name.length > 60) findings += "Name must be 1–60 characters"
        if (m.description.length > 400) findings += "Description is longer than 400 characters"
        if (m.type != ScriptPluginManifest.TYPE_TOOL) findings += "Module type must be 'tool'"
    }

    private fun checkTools(m: ScriptPluginManifest, kind: ProposalKind, target: String?, findings: MutableList<String>) {
        if (m.tools.isEmpty() || m.tools.size > MAX_TOOLS) findings += "A module must declare 1–$MAX_TOOLS tools"
        val names = m.tools.map { it.name }
        if (names.toSet().size != names.size) findings += "Tool names must be unique"
        m.tools.forEach { tool ->
            if (!TOOL_NAME.matches(tool.name)) findings += "Tool name '${tool.name.take(40)}' must be snake_case, 3–48 chars"
            if (tool.description.isBlank() || tool.description.length > 400) {
                findings += "Tool '${tool.name}' needs a description of at most 400 characters"
            }
            if (tool.parameters.size > MAX_PARAMS) findings += "Tool '${tool.name}' declares more than $MAX_PARAMS parameters"
            tool.parameters.forEach { p ->
                if (!PARAM_NAME.matches(p.name)) findings += "Parameter '${p.name.take(40)}' of '${tool.name}' is not a valid name"
            }
        }
        if (m.overrides.toSet().size != m.overrides.size) findings += "Overrides must be unique"
        m.overrides.filter { it !in names }.forEach { findings += "Override '$it' must name one of the module's own tools" }

        when (kind) {
            ProposalKind.MODULE_FEATURE -> {
                if (m.overrides.isNotEmpty()) findings += "A new-feature module may not override existing tools"
            }
            ProposalKind.MODULE_FIX -> {
                if (target.isNullOrBlank()) {
                    findings += "A fix must name the tool it repairs"
                } else if (m.overrides != listOf(target)) {
                    findings += "A fix for '$target' must declare exactly \"overrides\": [\"$target\"]"
                }
            }
            ProposalKind.APP_CHANGE -> Unit
        }

        m.overrides.forEach { name ->
            // The live wiring only ever shadows first-party tools; say so now rather than
            // let the user install a fix that would never take effect.
            if (moduleOwning(name) != null) {
                findings += "Cannot override '$name': it belongs to another module, not a built-in"
                return@forEach
            }
            when (val decision = ToolOverridePolicy.evaluate(name, existingTool(name))) {
                is ToolOverridePolicy.Decision.Denied -> findings += "Cannot override '$name': ${decision.reason}"
                ToolOverridePolicy.Decision.Allowed -> {
                    // The model keeps seeing the built-in's schema, so the override
                    // must accept every parameter the built-in requires.
                    val required = existingTool(name)?.parameters?.filter { it.required }?.map { it.name }.orEmpty()
                    val offered = m.tools.firstOrNull { it.name == name }?.parameters?.map { it.name }?.toSet()
                        ?: return@forEach
                    (required - offered).takeIf { it.isNotEmpty() }?.let {
                        findings += "Override '$name' must accept the built-in's required parameters: ${it.joinToString()}"
                    }
                }
            }
        }
        m.tools.filter { it.name !in m.overrides }.forEach { tool ->
            // A new version of the same module may keep its own tool names.
            if (existingTool(tool.name) != null && moduleOwning(tool.name) != m.id) {
                findings += "Tool '${tool.name}' already exists; pick a new name"
            }
        }
        m.tools.forEach { tool ->
            val verdict = SkillGuard.vet(tool.description + "\n" + tool.parameters.joinToString("\n") { it.description })
            if (!verdict.ok) findings += "Tool '${tool.name}' text flagged by Skills Guard: ${verdict.flags.joinToString()}"
        }
    }

    private fun checkScript(m: ScriptPluginManifest, findings: MutableList<String>) {
        val js = m.main
        if (js.isBlank()) findings += "Module has no script"
        if (js.length > MAX_SCRIPT_CHARS) findings += "Script is longer than $MAX_SCRIPT_CHARS characters"
        FORBIDDEN_JS.forEach { (pattern, label) ->
            if (pattern.containsMatchIn(js)) findings += "Script uses $label, which is not allowed"
        }
        HERMES_MEMBER.findAll(js).map { it.groupValues[1] }.filter { it !in HERMES_API }.distinct().forEach {
            findings += "Script calls hermes.$it, which does not exist"
        }
        if (BARE_HERMES.containsMatchIn(js)) findings += "Use the hermes API directly (hermes.<api>); do not alias or index it"
        SECRET_LITERAL.takeIf { it.containsMatchIn(js) || it.containsMatchIn(m.description) }?.let {
            findings += "Script contains a credential-shaped literal"
        }
        val verdict = SkillGuard.vet(js)
        if (!verdict.ok) findings += "Script flagged by Skills Guard: ${verdict.flags.joinToString()}"
    }

    private fun checkPermissions(m: ScriptPluginManifest, findings: MutableList<String>) {
        val declared = m.permissions.toSet()
        (declared - ScriptPluginPermissions.ALL).forEach { findings += "Unknown permission '$it'" }
        val used = buildSet {
            if (USES_HTTP.containsMatchIn(m.main)) add(ScriptPluginPermissions.NETWORK)
            if (USES_READ.containsMatchIn(m.main)) add(ScriptPluginPermissions.DATA_READ)
            if (USES_WRITE.containsMatchIn(m.main)) add(ScriptPluginPermissions.DATA_WRITE)
        }
        (declared intersect ScriptPluginPermissions.ALL).minus(used).forEach {
            findings += "Permission '$it' is declared but never used — ask only for what the code needs"
        }
        used.minus(declared).forEach { findings += "Script uses '$it' without declaring it" }

        if (ScriptPluginPermissions.NETWORK in declared) {
            if (m.hosts.isEmpty()) findings += "A module with network access must list the exact hosts it calls"
            if (m.hosts.size > MAX_HOSTS) findings += "At most $MAX_HOSTS hosts"
        } else if (m.hosts.isNotEmpty()) {
            findings += "Hosts are listed but network access is not requested"
        }
        m.hosts.forEach { host ->
            if (host.startsWith("*.")) findings += "Wildcard host '$host' is not allowed for an evolution module"
            if (!ModuleHosts.isValidEntry(host)) findings += "Host '${host.take(60)}' is not a valid hostname"
        }
        if (m.overrides.isNotEmpty() && ScriptPluginPermissions.DATA_WRITE in declared) {
            findings += "A module that overrides a tool may not write user data"
        }
    }

    private fun checkTests(m: ScriptPluginManifest, tests: List<ModuleSmokeTest>, findings: MutableList<String>) {
        if (tests.isEmpty()) {
            findings += "The manifest must include smoke tests"
            return
        }
        if (tests.size > MAX_TESTS) findings += "At most $MAX_TESTS smoke tests"
        val names = m.tools.map { it.name }.toSet()
        tests.forEach { t ->
            if (t.tool !in names) findings += "A test calls '${t.tool.take(40)}', which the module does not declare"
            if (t.expectContains.isBlank() || t.expectContains.length > 500) {
                findings += "Every test needs a non-empty expectContains of at most 500 characters"
            }
        }
        (names - tests.map { it.tool }.toSet()).forEach { findings += "Tool '$it' has no smoke test" }
    }

    companion object {
        /**
         * The first key that appears twice in one object of [json] (already known to be
         * valid JSON), decoded, or null. A plain scan: strings are skipped as tokens and
         * a string is a key when it opens an object entry.
         */
        internal fun duplicateKey(json: String): String? {
            val objects = ArrayDeque<MutableSet<String>?>() // null marks an array
            var expectKey = false
            var i = 0
            while (i < json.length) {
                when (json[i]) {
                    '{' -> { objects.addLast(mutableSetOf()); expectKey = true }
                    '[' -> { objects.addLast(null); expectKey = false }
                    '}', ']' -> { objects.removeLastOrNull(); expectKey = false }
                    ',' -> expectKey = objects.lastOrNull() != null
                    '"' -> {
                        val start = i
                        i++
                        while (i < json.length && json[i] != '"') i += if (json[i] == '\\') 2 else 1
                        if (expectKey) {
                            val token = json.substring(start, minOf(i + 1, json.length))
                            val key = runCatching { STRICT.decodeFromString(String.serializer(), token) }.getOrDefault(token)
                            if (objects.lastOrNull()?.add(key) == false) return key
                            expectKey = false
                        }
                    }
                }
                i++
            }
            return null
        }

        const val MAX_MANIFEST_CHARS = 48 * 1024
        const val MAX_SCRIPT_CHARS = 32 * 1024
        const val MAX_TOOLS = 5
        const val MAX_PARAMS = 12
        const val MAX_TESTS = 20
        const val MAX_HOSTS = 3

        private val STRICT = Json { isLenient = false }

        private val ALLOWED_KEYS = setOf(
            "id", "name", "version", "author", "description", "type", "minAppVersion",
            "permissions", "hosts", "tools", "overrides", "main", "tests",
        )
        val MODULE_ID = Regex("^evo-[a-z0-9][a-z0-9-]{2,47}$")
        private val SEMVER = Regex("^\\d{1,4}\\.\\d{1,4}\\.\\d{1,6}$")
        val TOOL_NAME = Regex("^[a-z][a-z0-9_]{2,47}$")
        private val PARAM_NAME = Regex("^[a-zA-Z_][a-zA-Z0-9_]{0,47}$")

        private val HERMES_API = setOf("registerTool", "log", "http", "data")
        private val HERMES_MEMBER = Regex("\\bhermes\\s*\\.\\s*([A-Za-z_$][\\w$]*)")
        /** `hermes` not followed by a member access: aliasing, indexing, passing it around. */
        private val BARE_HERMES = Regex("\\bhermes\\b(?!\\s*\\.\\s*[A-Za-z_$])")
        private val USES_HTTP = Regex("\\bhermes\\s*\\.\\s*http\\s*\\.\\s*get\\b")
        private val USES_READ = Regex("\\bhermes\\s*\\.\\s*data\\s*\\.\\s*read\\b")
        private val USES_WRITE = Regex("\\bhermes\\s*\\.\\s*data\\s*\\.\\s*write\\b")

        /** Constructs that hide code from review or reach for the host. Rhino blocks Java anyway. */
        private val FORBIDDEN_JS: List<Pair<Regex, String>> = listOf(
            Regex("\\beval\\s*\\(") to "eval()",
            Regex("\\bFunction\\s*\\(") to "the Function constructor",
            Regex("\\bnew\\s+Function\\b") to "the Function constructor",
            Regex("\\bPackages\\b") to "Packages",
            Regex("\\b(?:java|javax|android|org\\.mozilla)\\s*\\.") to "Java class access",
            Regex("\\b(?:importPackage|importClass|JavaImporter|JavaAdapter)\\b") to "Java imports",
            Regex("\\bgetClass\\b") to "getClass",
            Regex("__(?:proto|defineGetter|defineSetter|lookupGetter|lookupSetter)__") to "prototype tampering",
            Regex("\\bconstructor\\s*\\.\\s*constructor\\b") to "constructor chaining",
            Regex("(?<![\\w$.])(?:require|importScripts|load|loadClass|XMLHttpRequest|fetch)\\s*\\(") to "a loader or raw network call",
        )

        private val SECRET_LITERAL = Regex(
            "sk-[A-Za-z0-9_-]{20,}|sk-ant-\\S+|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_\\w+|AKIA[0-9A-Z]{16}|" +
                "xox[baprs]-\\S+|AIza[0-9A-Za-z_-]{30,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|" +
                "(?i:bearer\\s+[A-Za-z0-9._-]{20,})",
        )
    }
}
