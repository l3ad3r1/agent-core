package com.hermes.agent.domain.skill

import com.hermes.agent.domain.model.Skill

/**
 * Which skills the app may rewrite on its own (the weekly improvement pass and the every-N-uses
 * refine). A manual "Refine from usage" is the user's call and is not bound by this.
 *
 * - Built-in skills ship with the app and are reseeded, so they are never edited.
 * - Skills installed from the Skills Hub are someone else's work, pinned to a reviewed commit; an
 *   automatic rewrite makes them drift from it (a Hub skill was cut to about half its size this
 *   way). Re-installing is how those are updated.
 * - A skill changed in the last [COOLDOWN_MS], by anyone, is left alone. Two "weekly" passes an
 *   hour apart each rewrote the previous pass's output, compounding what the first one lost.
 */
object SkillAutoEditPolicy {
    const val COOLDOWN_MS = 6L * 24 * 60 * 60 * 1000

    fun mayAutoEdit(skill: Skill, now: Long = System.currentTimeMillis()): Boolean =
        !skill.isBuiltIn && skill.sourceUrl == null && now - skill.updatedAt >= COOLDOWN_MS
}
