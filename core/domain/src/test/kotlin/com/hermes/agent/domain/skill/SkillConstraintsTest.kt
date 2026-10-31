package com.hermes.agent.domain.skill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillConstraintsTest {

    private fun result(body: String, baseline: String? = null) =
        SkillConstraints.validate(body, baseline)

    @Test
    fun `well-formed body passes all gates`() {
        val body = "# Purpose\nDo the thing.\n\n## Steps\n1. First\n2. Second"
        assertTrue(SkillConstraints.allPass(result(body)))
    }

    @Test
    fun `empty or stub body fails non_empty`() {
        val res = result("# x")
        assertFalse(SkillConstraints.allPass(res))
        assertFalse(res.first { it.name == "non_empty" }.passed)
    }

    @Test
    fun `oversized body fails size gate`() {
        val huge = "# H\n" + "a".repeat(SkillConstraints.MAX_SKILL_BYTES + 1)
        val res = result(huge)
        assertFalse(res.first { it.name == "size" }.passed)
    }

    @Test
    fun `disproportionate growth fails growth gate`() {
        val baseline = "# H\n" + "a".repeat(100)
        val grown = "# H\n" + "a".repeat(400) // 4x
        val res = result(grown, baseline)
        assertFalse(res.first { it.name == "growth" }.passed)
    }

    @Test
    fun `a rewrite that cuts a long skill roughly in half fails the shrink gate`() {
        // The Hub's canvas-design skill (about 12,000 characters) came back at about half that.
        val baseline = "# Skill\n" + "x".repeat(12_000)
        val halved = "# Skill\n" + "x".repeat(5_900)
        val res = result(halved, baseline)
        assertFalse(res.first { it.name == "shrink" }.passed)
        assertFalse(SkillConstraints.allPass(res))
    }

    @Test
    fun `a modest tidy passes the shrink gate`() {
        val baseline = "# Skill\n" + "x".repeat(5_000)
        val tidied = "# Skill\n" + "x".repeat(3_800) // about 76 percent
        assertTrue(result(tidied, baseline).first { it.name == "shrink" }.passed)
    }

    @Test
    fun `shrink is not judged for short skills`() {
        val baseline = "# H\n" + "a".repeat(300)
        val cut = "# H\n" + "a".repeat(100)
        assertTrue(result(cut, baseline).first { it.name == "shrink" }.passed)
    }

    @Test
    fun `body without heading fails structure gate`() {
        val body = "Just some prose with no markdown heading at all, long enough to pass length."
        val res = result(body)
        assertFalse(res.first { it.name == "structure" }.passed)
    }

    // The description gate only runs when a description is supplied, so
    // body-only callers keep their existing set of gates.
    private val goodBody = "# Purpose\nDo the thing.\n\n## Steps\n1. First\n2. Second"

    @Test
    fun `no description gate when none is supplied`() {
        assertTrue(result(goodBody).none { it.name == "description" })
    }

    @Test
    fun `sensible description passes`() {
        val res = SkillConstraints.validate(
            goodBody,
            description = "Explains git commands in two sentences",
        )
        assertTrue(SkillConstraints.allPass(res))
    }

    @Test
    fun `multi-line description fails`() {
        // A newline would terminate the YAML scalar and split the frontmatter.
        val res = SkillConstraints.validate(goodBody, description = "First line\nSecond line")
        assertFalse(res.first { it.name == "description" }.passed)
    }

    @Test
    fun `too-short description fails`() {
        val res = SkillConstraints.validate(goodBody, description = "git")
        assertFalse(res.first { it.name == "description" }.passed)
    }

    @Test
    fun `over-long description fails`() {
        val res = SkillConstraints.validate(
            goodBody,
            description = "x".repeat(SkillDoc.MAX_DESCRIPTION_LENGTH + 1),
        )
        assertFalse(res.first { it.name == "description" }.passed)
    }
}
