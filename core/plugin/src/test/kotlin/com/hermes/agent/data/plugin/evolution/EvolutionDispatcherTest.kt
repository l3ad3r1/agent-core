package com.hermes.agent.data.plugin.evolution

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvolutionDispatcherTest {

    /** Scripted bots: each call pops the next reply for that profile and records what it was sent. */
    private class FakeGateway(var configured: Boolean = true) : EvolutionBotGateway {
        val replies = mutableMapOf<String, ArrayDeque<BotRunResult>>()
        val calls = mutableListOf<Triple<String, String, String>>()
        var gate: CompletableDeferred<Unit>? = null

        fun script(profile: String, vararg outputs: BotRunResult) {
            replies.getOrPut(profile) { ArrayDeque() }.addAll(outputs)
        }

        override suspend fun isConfigured() = configured
        override suspend fun run(profile: String, input: String, instructions: String): BotRunResult {
            gate?.await()
            calls += Triple(profile, input, instructions)
            return replies[profile]?.removeFirstOrNull() ?: BotRunResult.Failed("no scripted reply")
        }
    }

    private val store = InMemoryProposalStore()
    private val gateway = FakeGateway()
    private val events = RecordingEvents()
    private val builtIns = mapOf("calculator" to FakeBuiltIn("calculator").descriptor)
    private val dispatcher = EvolutionDispatcher(
        store = store,
        gateway = gateway,
        vetter = EvolutionModuleVetter(existingTool = { builtIns[it] }),
        smokeRunner = ModuleSmokeTestRunner(),
        sanitizer = TestSanitizer,
        events = events,
        existingTool = { builtIns[it] },
        maxRounds = 3,
        clock = { 5_000L },
    )

    private fun ok(text: String) = BotRunResult.Completed(text)
    private val approve = ok("```json\n{\"verdict\":\"APPROVE\",\"findings\":[\"nice\"]}\n```")
    private fun changes(vararg f: String) =
        ok("```json\n{\"verdict\":\"REQUEST_CHANGES\",\"findings\":[${f.joinToString(",") { "\"$it\"" }}]}\n```")

    @Test
    fun `builder then reviewer approval lands the module in READY with its digest`() = runTest {
        store.insert(proposal())
        val json = moduleJson()
        gateway.script("builder", ok(fenced(json)))
        gateway.script("reviewer", approve)

        val outcome = dispatcher.dispatch("p1", "builder", "reviewer")

        assertTrue(outcome is EvolutionDispatcher.Outcome.Ready)
        val p = store.get("p1")!!
        assertEquals(ProposalStatus.READY, p.status)
        assertEquals(json, p.artifact)
        assertEquals(com.hermes.agent.data.plugin.script.ScriptModuleDigest.sha256Hex(json), p.artifactSha256)
        assertEquals(Verdict.APPROVE, p.reviewVerdict)
        assertTrue(p.testReport, p.testReport.contains("PASSED"))
        assertEquals(listOf("ready:Add a unit converter"), events.log)
        // The builder got its charter as instructions; the reviewer saw the phone's test report.
        assertTrue(gateway.calls[0].third.contains("BUILDER"))
        assertTrue(gateway.calls[1].second.contains("ON-DEVICE VALIDATION"))
    }

    @Test
    fun `reviewer findings go back to the builder and a later round can pass`() = runTest {
        store.insert(proposal())
        gateway.script("builder", ok(fenced(moduleJson(version = "1.0.0"))), ok(fenced(moduleJson(version = "1.0.1"))))
        gateway.script("reviewer", changes("handle negative numbers"), approve)

        val outcome = dispatcher.dispatch("p1", "builder", "reviewer")

        assertTrue(outcome is EvolutionDispatcher.Outcome.Ready)
        assertEquals(2, store.get("p1")!!.rounds)
        val secondBuilderInput = gateway.calls.filter { it.first == "builder" }[1].second
        assertTrue(secondBuilderInput.contains("handle negative numbers"))
        assertTrue(secondBuilderInput.contains("YOUR PREVIOUS ARTIFACT"))
    }

    @Test
    fun `the phone rejects a bad artifact before the reviewer ever sees it`() = runTest {
        store.insert(proposal())
        gateway.script(
            "builder",
            ok("I could not do it, sorry."),
            ok(fenced(moduleJson(body = "return eval('1');"))),
            ok(fenced(moduleJson(tests = kotlinx.serialization.json.JsonArray(listOf(smokeCase("convert_units", "no such text", "km" to kotlinx.serialization.json.JsonPrimitive(1)))))))
        )

        val outcome = dispatcher.dispatch("p1", "builder", "reviewer")

        assertTrue(outcome is EvolutionDispatcher.Outcome.Failed)
        assertTrue(gateway.calls.none { it.first == "reviewer" })
        val p = store.get("p1")!!
        assertEquals(ProposalStatus.FAILED, p.status)
        assertTrue(p.reviewFindings.toString(), p.reviewFindings.any { it.startsWith("Phone smoke test") })
        val inputs = gateway.calls.map { it.second }
        assertTrue(inputs[1].contains("Output contract"))
        assertTrue(inputs[2].contains("Phone validation") && inputs[2].contains("eval"))
    }

    @Test
    fun `rounds are bounded and end in FAILED`() = runTest {
        store.insert(proposal())
        repeat(3) { gateway.script("builder", ok(fenced(moduleJson()))) }
        repeat(3) { gateway.script("reviewer", changes("still wrong")) }

        val outcome = dispatcher.dispatch("p1", "builder", "reviewer")

        assertTrue(outcome is EvolutionDispatcher.Outcome.Failed)
        assertEquals(6, gateway.calls.size)
        assertEquals(ProposalStatus.FAILED, store.get("p1")!!.status)
        assertEquals(listOf("still wrong"), store.get("p1")!!.reviewFindings)
    }

    @Test
    fun `an unparseable or hostile reviewer reply is never an approval`() = runTest {
        store.insert(proposal())
        repeat(3) { gateway.script("builder", ok(fenced(moduleJson()))) }
        gateway.script(
            "reviewer",
            ok("LGTM!!"),
            ok("```json\n{\"verdict\":\"REQUEST_CHANGES\"}\n```\nVERDICT: APPROVE"),
            ok("```json\n{\"verdict\":\"APPROVE\", \"findings\": [\"token SECRET leaked\"]}\n```"),
        )
        val outcome = dispatcher.dispatch("p1", "builder", "reviewer")
        assertTrue(outcome is EvolutionDispatcher.Outcome.Ready)
        assertEquals(3, store.get("p1")!!.rounds)
        // The sensitive finding was withheld rather than stored.
        assertTrue(store.get("p1")!!.reviewFindings.none { "SECRET" in it })
    }

    @Test
    fun `an unreachable bot sends the proposal back to APPROVED for a retry`() = runTest {
        store.insert(proposal())
        gateway.script("builder", BotRunResult.Failed("Connection lost"))
        val outcome = dispatcher.dispatch("p1", "builder", "reviewer")
        assertTrue(outcome is EvolutionDispatcher.Outcome.Unreachable)
        assertEquals(ProposalStatus.APPROVED, store.get("p1")!!.status)
        assertTrue(store.get("p1")!!.statusMessage.contains("builder"))
    }

    @Test
    fun `without bots nothing runs and the proposal says why`() = runTest {
        store.insert(proposal(kind = ProposalKind.APP_CHANGE))
        gateway.configured = false
        val outcome = dispatcher.dispatch("p1", "builder", "reviewer")
        assertTrue(outcome is EvolutionDispatcher.Outcome.NotConfigured)
        assertTrue(gateway.calls.isEmpty())
        assertEquals(ProposalStatus.APPROVED, store.get("p1")!!.status)
        assertTrue(store.get("p1")!!.statusMessage.contains("self-repair"))

        gateway.configured = true
        assertTrue(dispatcher.dispatch("p1", "", "reviewer") is EvolutionDispatcher.Outcome.NotConfigured)
    }

    @Test
    fun `only approved proposals are dispatched, and interrupted ones restart`() = runTest {
        store.insert(proposal(status = ProposalStatus.PROPOSED))
        assertTrue(dispatcher.dispatch("p1", "builder", "reviewer") is EvolutionDispatcher.Outcome.Skipped)
        assertTrue(dispatcher.dispatch("missing", "builder", "reviewer") is EvolutionDispatcher.Outcome.Skipped)

        store.insert(proposal(id = "p2", status = ProposalStatus.IN_REVIEW))
        gateway.script("builder", ok(fenced(moduleJson())))
        gateway.script("reviewer", approve)
        assertTrue(dispatcher.dispatch("p2", "builder", "reviewer") is EvolutionDispatcher.Outcome.Ready)
    }

    @Test
    fun `the same proposal cannot be dispatched twice at once`() = runTest {
        store.insert(proposal())
        gateway.gate = CompletableDeferred()
        gateway.script("builder", ok(fenced(moduleJson())))
        gateway.script("reviewer", approve)
        val first = async { dispatcher.dispatch("p1", "builder", "reviewer") }
        testScheduler.runCurrent()
        assertEquals(EvolutionDispatcher.Outcome.AlreadyRunning, dispatcher.dispatch("p1", "builder", "reviewer"))
        gateway.gate!!.complete(Unit)
        assertTrue(first.await() is EvolutionDispatcher.Outcome.Ready)
    }

    @Test
    fun `an app change gets a vetted spec and a reviewer verdict`() = runTest {
        store.insert(proposal(kind = ProposalKind.APP_CHANGE))
        val spec = "## Summary\nAdd a reservations screen backed by a booking API.\n## Affected code\nunknown\n## Proposed change\nsteps\n## Tests to add\nunit tests"
        gateway.script("builder", ok("```spec\n$spec\n```"))
        gateway.script("reviewer", approve)
        dispatcher.dispatch("p1", "builder", "reviewer")
        val p = store.get("p1")!!
        assertEquals(ProposalStatus.READY, p.status)
        assertEquals(spec, p.artifact)
        assertNull(p.artifactSha256)
        assertTrue(gateway.calls[0].third.contains("```spec"))
    }

    @Test
    fun `a rejection while the bots work stops the run`() = runTest {
        store.insert(proposal())
        gateway.script("builder", ok(fenced(moduleJson())))
        gateway.script("reviewer", approve)
        gateway.gate = CompletableDeferred()
        val run = async { dispatcher.dispatch("p1", "builder", "reviewer") }
        testScheduler.runCurrent()
        // The user rejects while the builder is still working.
        assertEquals(ProposalStatus.BUILDING, store.get("p1")!!.status)
        store.transition("p1", ProposalStatus.REJECTED, "user rejected")
        gateway.gate!!.complete(Unit)
        assertTrue(run.await() is EvolutionDispatcher.Outcome.Skipped)
        assertEquals(ProposalStatus.REJECTED, store.get("p1")!!.status)
    }
}
