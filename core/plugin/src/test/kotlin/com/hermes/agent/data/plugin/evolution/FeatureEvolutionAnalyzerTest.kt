package com.hermes.agent.data.plugin.evolution

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureEvolutionAnalyzerTest {

    private val failingSearch = UsageSnapshot(
        toolCalls = List(4) { ToolCallRecord("web_search", false, "HTTP 429", it.toLong()) },
        messages = emptyList(),
        skillRefinements = emptyMap(),
        builtInTools = setOf("web_search", "calculator"),
    )

    private val store = InMemoryProposalStore()
    private val events = RecordingEvents()
    private var reply: Result<String> = Result.success("NO_PROPOSALS")
    private val prompts = mutableListOf<String>()
    private var ids = 0

    private fun analyzer(snapshot: UsageSnapshot = failingSearch) = FeatureEvolutionAnalyzer(
        snapshots = { snapshot },
        miner = UsageSignalMiner(TestSanitizer),
        llm = { _, user -> prompts += user; reply },
        store = store,
        builtInTools = { snapshot.builtInTools },
        events = events,
        appName = "Hermes",
        clock = { 1_000_000L },
        newId = { "id${++ids}" },
    )

    private fun proposals(vararg items: String) = Result.success("```json\n{\"proposals\":[${items.joinToString(",")}]}\n```")
    private val fix = """{"signal":1,"title":"Back off on search rate limits","problem":"web_search fails with 429",
        "kind":"MODULE_FIX","target_tool":"web_search","acceptance_criteria":["Retries once","Same parameters"]}"""

    @Test
    fun `signals become persisted PROPOSED proposals and the user is notified`() = runTest {
        reply = proposals(fix)
        val outcome = analyzer().analyze(notify = true)

        val created = (outcome as FeatureEvolutionAnalyzer.Outcome.Created).proposals.single()
        assertEquals(ProposalStatus.PROPOSED, created.status)
        assertEquals(ProposalKind.MODULE_FIX, created.kind)
        assertEquals("web_search", created.targetTool)
        assertTrue(created.signalKey.startsWith("tool_failure:web_search:"))
        assertEquals(listOf(created), store.all())
        assertEquals(listOf("proposals:1"), events.log)
        assertTrue(prompts.single().contains("TOOL_FAILURE"))
    }

    @Test
    fun `a signal that already has a proposal is not sent to the model again`() = runTest {
        reply = proposals(fix)
        analyzer().analyze(notify = false)
        prompts.clear()

        val again = analyzer().analyze(notify = false)

        assertTrue(again is FeatureEvolutionAnalyzer.Outcome.NothingNew)
        assertTrue(prompts.isEmpty())
        assertEquals(1, store.all().size)
    }

    @Test
    fun `no history, a failing model and an empty reply are reported, not thrown`() = runTest {
        val empty = UsageSnapshot(emptyList(), emptyList(), emptyMap(), emptySet())
        assertEquals(FeatureEvolutionAnalyzer.Outcome.NoSignals, analyzer(empty).analyze(false))

        reply = Result.failure(IllegalStateException("Every configured cloud model failed"))
        assertTrue(analyzer().analyze(false) is FeatureEvolutionAnalyzer.Outcome.Failed)

        reply = Result.success("```json\n{\"proposals\": [{\"title\": 1}]}\n```")
        assertTrue(analyzer().analyze(false) is FeatureEvolutionAnalyzer.Outcome.NothingNew)
        assertTrue(store.all().isEmpty())
        assertTrue(events.log.isEmpty())
    }

    @Test
    fun `a fix for a tool that does not exist becomes a new module`() = runTest {
        reply = proposals(fix.replace("\"target_tool\":\"web_search\"", "\"target_tool\":\"teleport\""))
        val created = (analyzer().analyze(false) as FeatureEvolutionAnalyzer.Outcome.Created).proposals.single()
        assertEquals(ProposalKind.MODULE_FEATURE, created.kind)
        assertNull(created.targetTool)
    }

    @Test
    fun `two drafts for the same signal produce one proposal`() = runTest {
        reply = proposals(fix, fix.replace("Back off", "Also back off"))
        val created = (analyzer().analyze(false) as FeatureEvolutionAnalyzer.Outcome.Created).proposals
        assertEquals(1, created.size)
        assertFalse(store.all().any { it.title.startsWith("Also") })
    }

    @Test
    fun `transitions are closed`() {
        assertTrue(ProposalStatus.PROPOSED.canMoveTo(ProposalStatus.APPROVED))
        assertFalse(ProposalStatus.PROPOSED.canMoveTo(ProposalStatus.INSTALLED))
        assertFalse(ProposalStatus.REJECTED.canMoveTo(ProposalStatus.APPROVED))
        assertFalse(ProposalStatus.FAILED.canMoveTo(ProposalStatus.INSTALLED))
        assertTrue(ProposalStatus.INSTALLED.canMoveTo(ProposalStatus.ROLLED_BACK))
        assertFalse(ProposalStatus.READY.canMoveTo(ProposalStatus.ROLLED_BACK))
        ProposalStatus.entries.forEach { assertFalse(ProposalStatus.REJECTED.canMoveTo(it)) }
    }

    @Test
    fun `transition refuses a disallowed move and writes nothing`() = runTest {
        store.insert(proposal(status = ProposalStatus.PROPOSED))
        assertNull(store.transition("p1", ProposalStatus.INSTALLED, "nope"))
        assertEquals(ProposalStatus.PROPOSED, store.get("p1")!!.status)
        val moved = store.transition("p1", ProposalStatus.APPROVED, "ok", now = 42L)!!
        assertEquals(42L, moved.updatedAt)
        assertEquals("ok", store.get("p1")!!.statusMessage)
    }
}
