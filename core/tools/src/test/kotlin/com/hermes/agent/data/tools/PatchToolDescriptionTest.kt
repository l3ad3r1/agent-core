package com.hermes.agent.data.tools

import com.hermes.agent.data.local.FileCheckpointStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchToolDescriptionTest {

    @Test
    fun `the SEARCH REPLACE example in the description is one the patcher accepts`() {
        // On the tablet the model, told only "SEARCH/REPLACE block", used four-character
        // markers and failed three times before falling back to a unified diff.
        val description = PatchFileTool(null, null, FileCheckpointStore(kotlin.io.path.createTempDirectory().toFile()), null)
            .descriptor.parameters.first { it.name == "patch" }.description
        val example = Regex("\"(<<<<<<< SEARCH.*?>>>>>>> REPLACE)\"").find(description)?.groupValues?.get(1)
            ?.replace("\\n", "\n")
        assertTrue(description, example != null)

        val patch = example!!.replace("<old lines>", "\t- dal   ").replace("<new lines>", "\t- toor dal   ")
        val result = FuzzyPatcher.applyPatch("shopping list\n\t- dal   \n  - tea\n", patch)

        assertTrue("$result for patch <$patch>", result.success)
        assertEquals("shopping list\n\t- toor dal   \n  - tea\n", result.newContent)
    }
}
