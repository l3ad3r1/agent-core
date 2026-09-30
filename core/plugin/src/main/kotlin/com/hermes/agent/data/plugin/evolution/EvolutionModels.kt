package com.hermes.agent.data.plugin.evolution

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Feature evolution: the app notices from its own usage where it falls short,
 * turns that into proposals, has one bot build a fix and another review it, and
 * ships approved fixes on the fly as hot-loadable script modules.
 *
 * Everything in this package is framework-agnostic engine code shared by both
 * apps. Persistence, the desktop gateway, notifications and UI are supplied by
 * the host app through the small interfaces declared alongside each class.
 */

/** What kind of usage evidence a signal was mined from. */
enum class SignalKind(val label: String) {
    TOOL_FAILURE("Tool keeps failing"),
    CAPABILITY_GAP("Agent could not help"),
    REPEATED_REQUEST("Frequently repeated request"),
    SKILL_CHURN("Skill refined repeatedly"),
    UNUSED_TOOL("Built-in tools never used"),
}

/**
 * One ranked piece of evidence. [examples] are already sanitized by the host's
 * [EvidenceSanitizer]; nothing secret-looking survives into a signal.
 */
data class EvolutionSignal(
    val kind: SignalKind,
    /** Stable identity used to avoid proposing the same thing twice. */
    val key: String,
    val count: Int,
    val score: Double,
    val summary: String,
    val examples: List<String> = emptyList(),
    /** The tool the evidence is about, when there is exactly one. */
    val tool: String? = null,
)

/** How a proposal would ship. */
enum class ProposalKind(val label: String) {
    /** Repairs an existing tool with a script module that overrides it. */
    MODULE_FIX("Module fix"),

    /** Adds a new capability as a hot-loadable script module. */
    MODULE_FEATURE("New module"),

    /** Needs Kotlin: filed to the self-repair pipeline, ships as a test build. */
    APP_CHANGE("App change");

    val hotLoadable: Boolean get() = this != APP_CHANGE

    companion object {
        fun parse(raw: String?): ProposalKind? {
            val norm = raw?.trim()?.uppercase()?.replace('-', '_')?.replace(' ', '_') ?: return null
            return entries.firstOrNull { it.name == norm }
        }
    }
}

/**
 * Proposal lifecycle. Transitions are closed: [canMoveTo] is the single source of
 * truth and stores refuse anything else, so a bug or a hostile bot reply can
 * never, say, move a rejected proposal straight to installed.
 */
enum class ProposalStatus(val label: String, val terminal: Boolean = false) {
    PROPOSED("Proposed"),
    APPROVED("Approved"),
    BUILDING("Building"),
    IN_REVIEW("In review"),
    READY("Ready to install"),
    INSTALLED("Installed"),
    REJECTED("Rejected", terminal = true),
    FAILED("Failed"),
    ROLLED_BACK("Rolled back");

    fun canMoveTo(next: ProposalStatus): Boolean = next in (TRANSITIONS[this] ?: emptySet())

    companion object {
        private val TRANSITIONS: Map<ProposalStatus, Set<ProposalStatus>> = mapOf(
            PROPOSED to setOf(APPROVED, REJECTED),
            // APPROVED -> READY only for an app change filed without bots.
            APPROVED to setOf(BUILDING, READY, REJECTED),
            // Back to APPROVED when the bots were unreachable: not the proposal's fault.
            // REJECTED cancels a running build; the dispatcher stops at its next step.
            BUILDING to setOf(IN_REVIEW, BUILDING, FAILED, APPROVED, REJECTED),
            IN_REVIEW to setOf(BUILDING, READY, FAILED, APPROVED, REJECTED),
            READY to setOf(INSTALLED, REJECTED, FAILED),
            INSTALLED to setOf(ROLLED_BACK),
            FAILED to setOf(APPROVED, REJECTED),
            ROLLED_BACK to setOf(APPROVED, REJECTED),
            REJECTED to emptySet(),
        )

        /** Statuses in which a proposal still occupies its signal (no duplicate proposals). */
        val OPEN: Set<ProposalStatus> = setOf(PROPOSED, APPROVED, BUILDING, IN_REVIEW, READY, INSTALLED)

        fun parse(raw: String?): ProposalStatus? = entries.firstOrNull { it.name == raw }
    }
}

/** The reviewer bot's decision. */
enum class Verdict { APPROVE, REQUEST_CHANGES }

data class ReviewVerdict(val verdict: Verdict, val findings: List<String>) {
    val approved: Boolean get() = verdict == Verdict.APPROVE
}

/**
 * A proposal and everything the pipeline has learned about it. Immutable; the
 * store persists copies. Text fields that came from a bot are untrusted and were
 * size-capped and sanitized on the way in.
 */
data class EvolutionProposal(
    val id: String,
    val title: String,
    val problem: String,
    val evidence: String,
    val kind: ProposalKind,
    val acceptanceCriteria: List<String>,
    /** The existing tool a [ProposalKind.MODULE_FIX] repairs. */
    val targetTool: String? = null,
    val signalKey: String,
    val status: ProposalStatus = ProposalStatus.PROPOSED,
    val statusMessage: String = "",
    /** Builder output: the exact manifest JSON (module kinds) or the change spec (app change). */
    val artifact: String? = null,
    /** SHA-256 of [artifact] for module kinds — what the user approves and installLocal pins. */
    val artifactSha256: String? = null,
    val reviewVerdict: Verdict? = null,
    val reviewFindings: List<String> = emptyList(),
    /** Human-readable result of the on-device validation and smoke tests. */
    val testReport: String = "",
    val rounds: Int = 0,
    val moduleId: String? = null,
    val issueUrl: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** One smoke case a builder must ship with a module: call [tool], expect [expectContains] in the output. */
@Serializable
data class ModuleSmokeTest(
    val tool: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val expectContains: String,
)

/** The `tests` array that rides along in an evolution module manifest. */
@Serializable
internal data class ModuleSmokeTests(val tests: List<ModuleSmokeTest> = emptyList())

/** One row of the version history kept for every evolution module, newest last. */
data class EvolutionModuleVersion(
    val id: String,
    val moduleId: String,
    val proposalId: String,
    val version: String,
    val manifestJson: String,
    val sha256: String,
    val grantedPermissions: Set<String>,
    val installedAt: Long,
    val active: Boolean,
)

/** Proposal persistence, implemented by the host app (Room in both apps). */
interface EvolutionProposalStore {
    suspend fun get(id: String): EvolutionProposal?
    suspend fun all(): List<EvolutionProposal>
    suspend fun insert(proposal: EvolutionProposal)

    /** Writes [proposal] as-is. Callers go through [transition]; this is the raw write. */
    suspend fun update(proposal: EvolutionProposal)
    suspend fun findInstalledByModule(moduleId: String): EvolutionProposal?
}

/**
 * Moves [id] to [to], applying [mutate] to the stored copy, if and only if the
 * transition is allowed. Returns the stored proposal, or null when the proposal
 * is missing or the transition is not allowed (nothing is written then).
 */
suspend fun EvolutionProposalStore.transition(
    id: String,
    to: ProposalStatus,
    message: String,
    now: Long = System.currentTimeMillis(),
    mutate: (EvolutionProposal) -> EvolutionProposal = { it },
): EvolutionProposal? {
    val current = get(id) ?: return null
    if (!current.status.canMoveTo(to)) return null
    val next = mutate(current).copy(status = to, statusMessage = message.take(MAX_STATUS_MESSAGE), updatedAt = now)
    update(next)
    return next
}

/** Version history for evolution modules, implemented by the host app. */
interface EvolutionModuleVersionStore {
    suspend fun versions(moduleId: String): List<EvolutionModuleVersion>
    suspend fun record(version: EvolutionModuleVersion)
    suspend fun markActive(moduleId: String, versionId: String?)
}

/** User-visible events the host turns into notifications. */
interface EvolutionEvents {
    fun proposalsReady(count: Int)
    fun moduleReady(title: String)
    fun overrideReverted(toolName: String, moduleId: String, reason: String)
}

internal const val MAX_STATUS_MESSAGE = 600
