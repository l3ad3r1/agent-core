package com.hermes.agent.domain.skill

import com.hermes.agent.domain.model.Skill
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillAutoEditPolicyTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun skill(
        builtIn: Boolean = false,
        sourceUrl: String? = null,
        updatedDaysAgo: Long = 30,
        pinned: Boolean = false,
    ) = Skill(
        id = "s", name = "n", description = "A description long enough", version = "1.0.0", content = "# H\nbody",
        category = "c", tags = emptyList(), isBuiltIn = builtIn, createdAt = 0, updatedAt = now - updatedDaysAgo * day,
        sourceUrl = sourceUrl, pinned = pinned,
    )

    @Test
    fun `a locked skill is never edited automatically`() =
        assertFalse(SkillAutoEditPolicy.mayAutoEdit(skill(pinned = true, updatedDaysAgo = 365), now))

    @Test
    fun `a user-made skill untouched for a while may be edited`() =
        assertTrue(SkillAutoEditPolicy.mayAutoEdit(skill(), now))

    @Test
    fun `built-in skills are never edited`() =
        assertFalse(SkillAutoEditPolicy.mayAutoEdit(skill(builtIn = true), now))

    @Test
    fun `hub-installed skills are never edited automatically`() =
        assertFalse(SkillAutoEditPolicy.mayAutoEdit(skill(sourceUrl = "https://raw.githubusercontent.com/anthropics/skills/x/SKILL.md"), now))

    @Test
    fun `a skill changed within the cooldown is left alone, one past it is not`() {
        assertFalse(SkillAutoEditPolicy.mayAutoEdit(skill(updatedDaysAgo = 0), now))
        assertFalse(SkillAutoEditPolicy.mayAutoEdit(skill(updatedDaysAgo = 5), now))
        assertTrue(SkillAutoEditPolicy.mayAutoEdit(skill(updatedDaysAgo = 7), now))
    }
}
