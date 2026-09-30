package com.hermes.agent.data.plugin.evolution

import com.hermes.agent.data.plugin.ScriptPluginRepository
import com.hermes.agent.data.plugin.script.ScriptModuleDigest
import com.hermes.agent.data.plugin.script.ScriptPluginEngine
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvolutionModuleInstallerTest {

    private val dao = FakeScriptPluginDao()
    private val registry = FakeToolRegistry()
    private val repository = ScriptPluginRepository(dao, ScriptPluginEngine(), registry, NoHost())
    private val store = InMemoryProposalStore()
    private val versions = InMemoryVersionStore()
    private val events = RecordingEvents()
    private var now = 10_000L
    private var ids = 0
    private val installer = EvolutionModuleInstaller(
        store, versions, repository,
        EvolutionModuleVetter({ registry.builtInDescriptor(it) }, { registry.moduleOwning(it) }),
        ModuleSmokeTestRunner(), events,
        clock = { now },
        newId = { "v${++ids}" },
    )

    private suspend fun ready(id: String, json: String): String {
        val sha = ScriptModuleDigest.sha256Hex(json)
        store.insert(proposal(id = id, status = ProposalStatus.READY).copy(artifact = json, artifactSha256 = sha))
        return sha
    }

    private suspend fun convert(km: Int): ToolResult =
        registry.byName("convert_units")!!.execute(mapOf("km" to JsonPrimitive(km)))

    @Test
    fun `review re-runs validation and smoke tests on the stored bytes`() = runTest {
        ready("p1", moduleJson())
        val review = installer.review("p1").getOrThrow()
        assertTrue(review.testReport, review.passed)
        assertTrue(review.permissions.isEmpty())
        assertEquals(ScriptModuleDigest.sha256Hex(moduleJson()), review.sha256)
    }

    @Test
    fun `install goes live immediately and records the version`() = runTest {
        val sha = ready("p1", moduleJson())

        val installed = installer.install("p1", sha).getOrThrow()

        assertEquals(ProposalStatus.INSTALLED, installed.status)
        assertEquals("evo-unit-converter", installed.moduleId)
        assertEquals("6.21 miles", convert(10).output)
        val row = dao.items.getValue("evo-unit-converter")
        assertEquals(sha, ScriptPluginRepository.pinnedDigest(row.sourceUrl))
        assertEquals(listOf(true), versions.items.map { it.active })
    }

    @Test
    fun `install refuses bytes other than the ones the user approved`() = runTest {
        ready("p1", moduleJson())
        val result = installer.install("p1", ScriptModuleDigest.sha256Hex(moduleJson(version = "9.9.9")))
        assertTrue(result.isFailure)
        assertTrue(dao.items.isEmpty())
        assertEquals(ProposalStatus.READY, store.get("p1")!!.status)
    }

    @Test
    fun `install refuses a stored artifact that was tampered with after review`() = runTest {
        val sha = ready("p1", moduleJson())
        store.update(store.get("p1")!!.copy(artifact = moduleJson(body = "return 'evil';")))
        assertTrue(installer.install("p1", sha).isFailure)
        assertTrue(dao.items.isEmpty())
    }

    @Test
    fun `install refuses a proposal that is not ready`() = runTest {
        val json = moduleJson()
        store.insert(proposal(status = ProposalStatus.PROPOSED).copy(artifact = json, artifactSha256 = ScriptModuleDigest.sha256Hex(json)))
        assertTrue(installer.install("p1", ScriptModuleDigest.sha256Hex(json)).isFailure)
        assertTrue(dao.items.isEmpty())
    }

    @Test
    fun `rollback restores the previous version, then removes the module`() = runTest {
        val v1 = moduleJson(version = "1.0.0")
        installer.install("p1", ready("p1", v1)).getOrThrow()
        now += 1_000
        val v2 = moduleJson(
            version = "1.1.0",
            body = "return 'v2 ' + args.km;",
            tests = kotlinx.serialization.json.JsonArray(listOf(smokeCase("convert_units", "v2 10", "km" to JsonPrimitive(10)))),
        )
        installer.install("p2", ready("p2", v2)).getOrThrow()
        assertTrue(convert(10).output.startsWith("v2"))
        assertTrue(store.get("p1")!!.statusMessage.contains("Superseded"))

        // The older one is no longer live, so it cannot be the one rolled back.
        assertTrue(installer.rollback("p1").isFailure)

        val rolled = installer.rollback("p2").getOrThrow()
        assertEquals(ProposalStatus.ROLLED_BACK, rolled.status)
        assertEquals("6.21 miles", convert(10).output)
        assertEquals(ScriptModuleDigest.sha256Hex(v1), ScriptPluginRepository.pinnedDigest(dao.items.getValue("evo-unit-converter").sourceUrl))
        assertEquals("v1", versions.items.single { it.active }.id)

        installer.rollback("p1").getOrThrow()
        assertTrue(dao.items.isEmpty())
        assertNull(registry.byName("convert_units"))
        assertTrue(versions.items.none { it.active })
    }

    @Test
    fun `a tripped override disables its module, marks it rolled back and notifies`() = runTest {
        installer.install("p1", ready("p1", moduleJson())).getOrThrow()
        installer.onOverrideTripped("evo-unit-converter", "convert_units", "3 consecutive failures")
        assertFalse(dao.items.getValue("evo-unit-converter").enabled)
        assertEquals(ProposalStatus.ROLLED_BACK, store.get("p1")!!.status)
        assertTrue(store.get("p1")!!.statusMessage.contains("Automatically reverted"))
        assertEquals(listOf("reverted:convert_units:evo-unit-converter"), events.log)
    }

    private class FakeFiler(override val isConfigured: Boolean = true) : AppChangeFiler {
        val filed = mutableListOf<Pair<String?, ReviewVerdict?>>()
        override suspend fun file(proposal: EvolutionProposal, spec: String?, verdict: ReviewVerdict?): Result<String> {
            filed += spec to verdict
            return Result.success("https://github.com/o/r/issues/7")
        }
    }

    @Test
    fun `an app change is filed with its reviewed spec, or without bots from APPROVED`() = runTest {
        val filer = FakeFiler()
        store.insert(
            proposal(id = "a1", kind = ProposalKind.APP_CHANGE, status = ProposalStatus.READY)
                .copy(artifact = "## Summary\nspec", reviewVerdict = Verdict.APPROVE),
        )
        val filed = installer.fileAppChange("a1", filer).getOrThrow()
        assertEquals("https://github.com/o/r/issues/7", filed.issueUrl)
        assertEquals("## Summary\nspec", filer.filed.single().first)
        assertTrue(installer.fileAppChange("a1", filer).isFailure) // not twice

        store.insert(proposal(id = "a2", kind = ProposalKind.APP_CHANGE, status = ProposalStatus.APPROVED))
        val direct = installer.fileAppChange("a2", filer).getOrThrow()
        assertEquals(ProposalStatus.READY, direct.status)
        assertNull(filer.filed.last().first)
        assertEquals(ProposalStatus.INSTALLED, installer.markAppChangeInstalled("a2").getOrThrow().status)

        assertTrue(installer.fileAppChange("p1", filer).isFailure)
        store.insert(proposal(id = "a3", kind = ProposalKind.APP_CHANGE, status = ProposalStatus.APPROVED))
        assertTrue(installer.fileAppChange("a3", FakeFiler(isConfigured = false)).isFailure)
    }
}
