package com.hermes.agent.data.plugin.script

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards on the JavaScript sandbox itself.
 *
 * The engine's runaway-script protection was inert for its whole life: Rhino
 * resets its instruction counter after every observer callback, so the value
 * the observer receives is always about the 10k threshold and was being
 * compared against a 5,000,000 budget it could never reach. Every test here
 * that involves an infinite loop is a regression test for that — each one runs
 * under an outer wall-clock timeout so a re-broken guard fails the suite
 * instead of hanging it.
 */
class ScriptPluginEngineTest {

    private fun spec(id: String, source: String) =
        ScriptPluginEngine.PluginSpec(id = id, source = source, permissions = emptySet())

    /**
     * Fails the test rather than hanging the suite if the guard stops working.
     * [runTest]'s own timeout is enforced in real time, which is what this
     * needs — the runaway scripts block a real thread on [Dispatchers.Default]
     * rather than suspending, so a virtual-time timeout would never fire.
     */
    private fun engineTest(block: suspend TestScope.() -> Unit) =
        runTest(timeout = GUARD_TEST_TIMEOUT, testBody = block)

    @Test
    fun `a well behaved module loads and its tool returns a value`() = engineTest {
        val engine = ScriptPluginEngine()
        val failures = engine.reload(
            listOf(
                spec(
                    "greeter",
                    """
                    hermes.registerTool('greet', function (args) {
                        return 'hello ' + args.name;
                    });
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(emptyList<String>(), failures)
        assertEquals(listOf("greet"), engine.registeredToolNames("greeter"))

        val result = engine.execute("greeter", "greet", mapOf("name" to JsonPrimitive("world")))
        assertEquals("hello world", result.getOrNull())
    }

    @Test
    fun `an infinite loop at load time is aborted and reported`() = engineTest {
        val engine = ScriptPluginEngine()
        val failures = engine.reload(listOf(spec("runaway", "while (true) { }")))

        assertEquals(1, failures.size)
        assertTrue("unexpected failure text: ${failures[0]}", failures[0].startsWith("runaway:"))
    }

    @Test
    fun `an infinite loop inside a tool is aborted`() = engineTest {
        val engine = ScriptPluginEngine()
        engine.reload(
            listOf(
                spec(
                    "spinner",
                    "hermes.registerTool('spin', function () { while (true) { } });",
                ),
            ),
        )

        val result = engine.execute("spinner", "spin", emptyMap())
        assertTrue("expected the spinning tool to fail", result.isFailure)
    }

    @Test
    fun `a script cannot swallow the abort with its own try catch`() = engineTest {
        val engine = ScriptPluginEngine()
        // If the abort were a plain Exception, Rhino would wrap it as a JS
        // exception and this catch would swallow it — the loop would then be
        // re-entered forever. It is an Error precisely so this cannot happen.
        val failures = engine.reload(
            listOf(
                spec(
                    "sneaky",
                    """
                    while (true) {
                        try { while (true) { } } catch (e) { }
                    }
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(1, failures.size)
    }

    @Test
    fun `one module spinning does not block another module's tools`() = engineTest {
        val engine = ScriptPluginEngine()
        engine.reload(
            listOf(
                spec("spinner", "hermes.registerTool('spin', function () { while (true) { } });"),
                spec("adder", "hermes.registerTool('add', function (a) { return a.x + a.y; });"),
            ),
        )

        // The spinner burns its whole budget while the adder runs. Under the
        // old single engine-wide mutex this could not even be expressed: the
        // adder would queue behind the spinner.
        val spinning = async { engine.execute("spinner", "spin", emptyMap()) }
        val quick = engine.execute("adder", "add", mapOf("x" to JsonPrimitive(2), "y" to JsonPrimitive(3)))

        assertEquals("5", quick.getOrNull())
        assertTrue(spinning.await().isFailure)
    }

    @Test
    fun `the budget resets between runs so repeated calls keep working`() = engineTest {
        val engine = ScriptPluginEngine()
        engine.reload(
            listOf(
                spec(
                    "worker",
                    """
                    hermes.registerTool('work', function () {
                        var total = 0;
                        for (var i = 0; i < 20000; i++) { total += i; }
                        return String(total);
                    });
                    """.trimIndent(),
                ),
            ),
        )

        // Each call costs well over one observer window, so a guard that failed
        // to reset would accumulate across calls and eventually abort.
        repeat(20) { attempt ->
            val result = engine.execute("worker", "work", emptyMap())
            assertTrue("call $attempt failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
            assertEquals("199990000", result.getOrNull())
        }
    }

    @Test
    fun `a module that aborts leaves the engine usable`() = engineTest {
        val engine = ScriptPluginEngine()
        engine.reload(
            listOf(
                spec(
                    "mixed",
                    """
                    hermes.registerTool('spin', function () { while (true) { } });
                    hermes.registerTool('ok', function () { return 'fine'; });
                    """.trimIndent(),
                ),
            ),
        )

        assertTrue(engine.execute("mixed", "spin", emptyMap()).isFailure)
        assertEquals("fine", engine.execute("mixed", "ok", emptyMap()).getOrNull())
    }

    @Test
    fun `java classes stay unreachable from a module`() = engineTest {
        val engine = ScriptPluginEngine()
        val failures = engine.reload(
            listOf(spec("escape", "var f = java.io.File; hermes.registerTool('x', function () { return 1; });")),
        )

        assertEquals(1, failures.size)
        assertFalse(engine.registeredToolNames("escape").contains("x"))
    }

    private companion object {
        /**
         * Generous next to the engine's own 5s deadline — this only has to
         * catch a guard that never fires at all.
         */
        val GUARD_TEST_TIMEOUT = 60.seconds
    }
}
