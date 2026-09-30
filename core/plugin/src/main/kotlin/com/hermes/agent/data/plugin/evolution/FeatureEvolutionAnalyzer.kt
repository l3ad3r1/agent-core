package com.hermes.agent.data.plugin.evolution

import timber.log.Timber
import java.util.UUID

/**
 * One text completion for the proposal pass. The host routes it to a cloud model
 * only (the on-device model does not plan self-modification) and returns a
 * failure rather than falling back.
 */
fun interface EvolutionLlm {
    suspend fun complete(system: String, user: String): Result<String>
}

/**
 * The analysis half of the loop: mine usage → one model pass → persisted
 * PROPOSED proposals. Nothing is built or changed here; every proposal waits for
 * the user's approval.
 */
class FeatureEvolutionAnalyzer(
    private val snapshots: UsageSnapshotSource,
    private val miner: UsageSignalMiner,
    private val llm: EvolutionLlm,
    private val store: EvolutionProposalStore,
    private val builtInTools: () -> Set<String>,
    private val events: EvolutionEvents,
    private val appName: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    sealed interface Outcome {
        data class Created(val proposals: List<EvolutionProposal>) : Outcome
        data object NoSignals : Outcome
        data class NothingNew(val reason: String) : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun analyze(notify: Boolean): Outcome {
        val snapshot = runCatching { snapshots.load(WINDOW_MILLIS) }
            .getOrElse { return Outcome.Failed("Could not read usage history: ${it.message}") }
        val signals = miner.mine(snapshot)
        if (signals.isEmpty()) return Outcome.NoSignals

        val now = clock()
        val existing = store.all()
        // A signal that already has a proposal is not proposed again — except when an
        // old attempt was rejected, failed or rolled back long enough ago.
        val taken = existing.filter { p ->
            p.status in ProposalStatus.OPEN || now - p.updatedAt < RESURFACE_AFTER_MILLIS
        }.map { it.signalKey }.toSet()
        val fresh = signals.filter { it.key !in taken }.take(MAX_SIGNALS_TO_MODEL)
        if (fresh.isEmpty()) return Outcome.NothingNew("Every current signal already has a proposal.")

        val tools = builtInTools()
        val raw = llm.complete(
            EvolutionCharters.PROPOSAL_SYSTEM,
            EvolutionCharters.proposalPrompt(appName, fresh, tools),
        ).getOrElse { return Outcome.Failed(it.message ?: "The model call failed") }

        val titles = existing.filter { it.status in ProposalStatus.OPEN }.map { it.title.lowercase() }.toMutableSet()
        val used = mutableSetOf<String>()
        val created = EvolutionProposalParser.parse(raw).mapNotNull { draft ->
            val signal = draft.signalIndex?.let { fresh.getOrNull(it - 1) }
            val key = signal?.key ?: "model:${UsageSignalMiner.fingerprint(draft.title.lowercase())}"
            if (key in taken || !used.add(key) || !titles.add(draft.title.lowercase())) return@mapNotNull null
            val kind = if (draft.kind == ProposalKind.MODULE_FIX && draft.targetTool !in tools) {
                ProposalKind.MODULE_FEATURE
            } else {
                draft.kind
            }
            EvolutionProposal(
                id = newId(),
                title = draft.title,
                problem = draft.problem,
                evidence = draft.evidence.ifBlank { signal?.summary.orEmpty() }.take(600),
                kind = kind,
                acceptanceCriteria = draft.acceptanceCriteria,
                targetTool = draft.targetTool.takeIf { kind == ProposalKind.MODULE_FIX },
                signalKey = key,
                status = ProposalStatus.PROPOSED,
                statusMessage = "Found by usage analysis. Approve it to have the bots build it.",
                createdAt = now,
                updatedAt = now,
            )
        }
        if (created.isEmpty()) return Outcome.NothingNew("The model found nothing worth proposing.")
        created.forEach { store.insert(it) }
        Timber.tag(TAG).i("created %d evolution proposal(s)", created.size)
        if (notify) events.proposalsReady(created.size)
        return Outcome.Created(created)
    }

    companion object {
        private const val TAG = "FeatureEvolution"
        const val WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val RESURFACE_AFTER_MILLIS = 90L * 24 * 60 * 60 * 1000
        const val MAX_SIGNALS_TO_MODEL = 8
    }
}
