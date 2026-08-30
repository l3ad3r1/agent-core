package com.hermes.agent.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyPatcherTest {

    @Test
    fun applyPatch_exactUnifiedDiff_replacesContent() {
        val original = """
            fun main() {
                println("Hello World")
            }
        """.trimIndent()

        val patch = """
            --- a/main.kt
            +++ b/main.kt
            @@ -1,3 +1,3 @@
             fun main() {
            -    println("Hello World")
            +    println("Hello Hermes")
             }
        """.trimIndent()

        val result = FuzzyPatcher.applyPatch(original, patch)
        assertTrue(result.success)
        assertEquals(1, result.hunksApplied)
        assertTrue(result.newContent.contains("println(\"Hello Hermes\")"))
    }

    @Test
    fun applyPatch_v4aFormat_appliesChanges() {
        val original = """
            Line 1
            Line 2 to remove
            Line 3
        """.trimIndent()

        val v4aPatch = """
            *** Begin Patch
            *** Update File: sample.txt
            @@ -1,3 +1,3 @@
             Line 1
            -Line 2 to remove
            +Line 2 new
             Line 3
            *** End Patch
        """.trimIndent()

        val result = FuzzyPatcher.applyPatch(original, v4aPatch)
        assertTrue(result.success)
        assertEquals(1, result.hunksApplied)
        assertTrue(result.newContent.contains("Line 2 new"))
    }

    @Test
    fun applyPatch_searchReplaceBlocks_appliesReplacement() {
        val original = """
            val port = 8080
            val host = "localhost"
            val timeout = 30
        """.trimIndent()

        val patch = """
            <<<<<<< SEARCH
            val port = 8080
            =======
            val port = 9090
            >>>>>>>
        """.trimIndent()

        val result = FuzzyPatcher.applyPatch(original, patch)
        assertTrue(result.success)
        assertEquals(1, result.hunksApplied)
        assertTrue(result.newContent.contains("val port = 9090"))
    }

    @Test
    fun applyPatch_fuzzyWhitespaceDrift_stillMatches() {
        val original = """
            class AppConfig {
                var isEnabled: Boolean = false
                var retryCount: Int = 3
            }
        """.trimIndent()

        // Patch has slightly different indentation or trailing space
        val patch = """
            @@ -1,4 +1,4 @@
            class AppConfig {
            -  var isEnabled: Boolean = false
            +  var isEnabled: Boolean = true
               var retryCount: Int = 3
            }
        """.trimIndent()

        val result = FuzzyPatcher.applyPatch(original, patch)
        assertTrue(result.success)
        assertEquals(1, result.hunksApplied)
        assertTrue(result.newContent.contains("isEnabled: Boolean = true"))
    }
}
