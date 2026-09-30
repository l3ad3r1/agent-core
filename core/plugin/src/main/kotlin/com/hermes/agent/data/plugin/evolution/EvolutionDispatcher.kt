package com.hermes.agent.data.plugin.evolution

import com.hermes.agent.domain.tool.ToolDescriptor
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs one agent run on a bot (a desktop gateway profile) and returns its final
 * text. Implemented by the host app over its gateway client; the dispatcher
 * never talks HTTP itself.
 */
interface EvolutionBotGateway {
    /** True when a gateway is configured at all; false means "no bots", not "bots are down". */
    suspend fun isConfigured(): Boolean

    suspend fun run(profile: String, input: String, instructions: String): BotRunResult
}

sealed interface BotRunResult {
    data class Completed(val output: String) : BotRunResult
    data class Failed(val message: String) : BotRunResult
}

/** Files an app change to the self-repair pipeline (a redacted issue). Implemented by the host. */
interface AppChangeFiler {
    val isConfigured: Boolean

    /** Returns the issue URL. [spec] is null when the change is filed without bots. */
    suspend fun file(proposal: EvolutionProposal, spec: String?, verdict: ReviewVerdict?): Result<String>
}

/**
 * The builder/reviewer loop for one approved proposal.
 *
 * Round by round: the builder bot produces an artifact; for a module the phone
 * vets it and runs its smoke tests in a scratch sandbox first (cheap and
 * authoritative, so the reviewer never sees something the phone would refuse);
 * then the reviewer bot returns a verdict. REQUEST_CHANGES — from the reviewer or
 * the phone — goes back to the builder, for at most [maxRounds] rounds, then the
 * proposal FAILS. An approved module lands in READY, where the user reviews and
 * installs it; nothing is installed here.
 */
class EvolutionDispatcher(
    private val store: EvolutionProposalStore,
    private val gateway: EvolutionBotGateway,
    private val vetter: EvolutionModuleVetter,
    private val smokeRunner: ModuleSmokeTestRunner,
    private val sanitizer: EvidenceSanitizer,
    private val events: EvolutionEvents,
    private val existingTool: (String) -> ToolDescriptor?,
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed interface Outcome {
        data class Ready(val proposal: EvolutionProposal) : Outcome
        data class Failed(val reason: String) : Outcome
        /** No bots set up; the proposal is left as it was, with a message saying so. */
        data class NotConfigured(val reason: String) : Outcome
        /** A bot could not be reached; the proposal went back to APPROVED for a retry. */
        data class Unreachable(val reason: String) : Outcome
        /** The proposal changed underneath us (rejected, missing, not approved). */
        data class Skipped(val reason: String) : Outcome
        data object AlreadyRunning : Outcome
    }

    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun dispatch(proposalId: String, builderProfile: String, reviewerProfile: String): Outcome {
        if (!inFlight.add(proposalId)) return Outcome.AlreadyRunning
        return try {
            run(proposalId, builderProfile.trim(), reviewerProfile.trim())
        } finally {
            inFlight.remove(proposalId)
        }
    }

    private suspend fun run(id: String, builder: String, reviewer: String): Outcome {
        var proposal = store.get(id) ?: return Outcome.Skipped("proposal no longer exists")
        var findings = emptyList<String>()
        var previous: String? = null
        var firstRound = 1
        // A run killed mid-way (process death, or the OS stopping the worker) left it
        // BUILDING/IN_REVIEW. The interrupted round counts as used, so a run that keeps
        // getting killed still ends after maxRounds instead of rebuilding forever.
        if (proposal.status == ProposalStatus.BUILDING || proposal.status == ProposalStatus.IN_REVIEW) {
            val used = proposal.rounds.coerceIn(0, maxRounds)
            if (used >= maxRounds) {
                val reason = "The build was interrupted in its last round ($used/$maxRounds)"
                store.transition(id, ProposalStatus.FAILED, reason, clock())
                    ?: return Outcome.Skipped("the proposal changed")
                return Outcome.Failed(reason)
            }
            proposal = store.transition(id, ProposalStatus.APPROVED, "Resuming an interrupted build", clock())
                ?: return Outcome.Skipped("could not resume")
            findings = proposal.reviewFindings
            previous = proposal.artifact
            firstRound = used + 1
        }
        if (proposal.status != ProposalStatus.APPROVED) {
            return Outcome.Skipped("only an approved proposal can be sent to the bots (it is ${proposal.status.label})")
        }
        if (builder.isEmpty() || reviewer.isEmpty() || !gateway.isConfigured()) {
            val why = "No builder/reviewer bots are set up — connect the desktop gateway and pick both bots in Feature evolution settings" +
                if (proposal.kind == ProposalKind.APP_CHANGE) ", or file this app change to the self-repair repo directly." else "."
            store.patchIf(id, ProposalStatus.APPROVED) { it.copy(statusMessage = why, updatedAt = clock()) }
            return Outcome.NotConfigured(why)
        }

        val target = proposal.targetTool?.let(existingTool)

        for (round in firstRound..maxRounds) {
            proposal = store.transition(
                id, ProposalStatus.BUILDING, "Round $round/$maxRounds: builder bot '$builder' is working", clock(),
            ) { it.copy(rounds = round) } ?: return Outcome.Skipped("the proposal changed while it was being built")

            val input = EvolutionCharters.builderInput(proposal, round, maxRounds, findings, previous, target)
            val built = when (val r = gateway.run(builder, input, EvolutionCharters.builderCharter(proposal.kind))) {
                is BotRunResult.Failed -> return unreachable(id, "builder", r.message)
                is BotRunResult.Completed -> r.output
            }

            val candidate = if (proposal.kind.hotLoadable) buildModule(proposal, built) else buildSpec(built)
            if (candidate is Candidate.Rejected) {
                findings = candidate.findings
                previous = candidate.artifact
                // Only while still BUILDING: a raw write would resurrect a proposal the user rejected meanwhile.
                store.patchIf(id, ProposalStatus.BUILDING) {
                    it.copy(reviewFindings = findings, testReport = candidate.report, updatedAt = clock())
                } ?: return Outcome.Skipped("the proposal changed while it was being built")
                Timber.tag(TAG).i("round %d of %s rejected on device: %s", round, id, findings.firstOrNull())
                continue
            }
            candidate as Candidate.Accepted

            proposal = store.transition(
                id, ProposalStatus.IN_REVIEW, "Round $round/$maxRounds: reviewer bot '$reviewer' is checking", clock(),
            ) {
                it.copy(artifact = candidate.artifact, artifactSha256 = candidate.sha256, testReport = candidate.report)
            } ?: return Outcome.Skipped("the proposal changed while it was in review")

            val reviewed = when (
                val r = gateway.run(
                    reviewer,
                    EvolutionCharters.reviewerInput(proposal, candidate.artifact, candidate.report),
                    EvolutionCharters.REVIEWER,
                )
            ) {
                is BotRunResult.Failed -> return unreachable(id, "reviewer", r.message)
                is BotRunResult.Completed -> r.output
            }
            val verdict = BotOutputParser.parseVerdict(reviewed).let { v ->
                v.copy(findings = v.findings.map { sanitizer.sanitize(it) ?: "(finding withheld: sensitive-looking content)" })
            }

            if (verdict.approved) {
                val ready = store.transition(
                    id, ProposalStatus.READY,
                    if (proposal.kind.hotLoadable) {
                        "Reviewer approved in round $round. Review the module and install it."
                    } else {
                        "Reviewer approved the change spec in round $round. File it to the self-repair repo to get a draft PR."
                    },
                    clock(),
                ) { it.copy(reviewVerdict = Verdict.APPROVE, reviewFindings = verdict.findings) }
                    ?: return Outcome.Skipped("the proposal changed during review")
                events.moduleReady(ready.title)
                return Outcome.Ready(ready)
            }
            findings = verdict.findings.ifEmpty { listOf("Reviewer requested changes without details") }
            previous = candidate.artifact
            store.patchIf(id, ProposalStatus.IN_REVIEW) {
                it.copy(reviewVerdict = Verdict.REQUEST_CHANGES, reviewFindings = findings, updatedAt = clock())
            } ?: return Outcome.Skipped("the proposal changed during review")
        }

        val reason = "No approved build after $maxRounds rounds"
        store.transition(id, ProposalStatus.FAILED, reason, clock()) { it.copy(reviewFindings = findings) }
        return Outcome.Failed(reason)
    }

    private sealed interface Candidate {
        data class Accepted(val artifact: String, val sha256: String?, val report: String) : Candidate
        data class Rejected(val findings: List<String>, val artifact: String?, val report: String) : Candidate
    }

    private suspend fun buildModule(proposal: EvolutionProposal, output: String): Candidate {
        val manifestJson = when (val e = BotOutputParser.extractModuleManifest(output)) {
            is BotOutputParser.Extraction.Invalid -> return Candidate.Rejected(listOf("Output contract: ${e.reason}"), null, "")
            is BotOutputParser.Extraction.Found -> e.text
        }
        val vet = vetter.vet(manifestJson, proposal.kind, proposal.targetTool)
        val manifest = vet.manifest
        if (!vet.ok || manifest == null) {
            return Candidate.Rejected(vet.findings.map { "Phone validation: $it" }, manifestJson, "Validation failed")
        }
        val smoke = smokeRunner.run(manifest, vet.tests, manifest.permissions.toSet())
        val report = "Static validation passed (sha256 ${vet.sha256.take(12)}…)\n${smoke.summary()}"
        if (!smoke.passed) {
            val failures = smoke.problems + smoke.results.filter { !it.passed }.map {
                "Smoke test for ${it.test.tool} ${it.test.arguments.toString().take(80)} failed: " +
                    (it.error ?: "output did not contain \"${it.test.expectContains.take(60)}\"")
            }
            return Candidate.Rejected(failures.map { "Phone smoke test: ${it.take(300)}" }, manifestJson, report)
        }
        return Candidate.Accepted(manifestJson, vet.sha256, report)
    }

    private fun buildSpec(output: String): Candidate {
        val spec = when (val e = BotOutputParser.extractChangeSpec(output)) {
            is BotOutputParser.Extraction.Invalid -> return Candidate.Rejected(listOf("Output contract: ${e.reason}"), null, "")
            is BotOutputParser.Extraction.Found -> e.text
        }
        // The spec leaves the device as an issue, so it goes through the same redaction as evidence.
        val clean = sanitizer.sanitize(spec)
            ?: return Candidate.Rejected(listOf("The spec contained sensitive-looking content; leave it out"), null, "")
        return Candidate.Accepted(clean, null, "")
    }

    private suspend fun unreachable(id: String, role: String, message: String): Outcome {
        val safe = sanitizer.sanitize(message)?.take(200) ?: "unavailable"
        val reason = "The $role bot could not finish: $safe. Try again later."
        store.transition(id, ProposalStatus.APPROVED, reason, clock())
        return Outcome.Unreachable(reason)
    }

    companion object {
        private const val TAG = "EvolutionDispatch"
        const val DEFAULT_MAX_ROUNDS = 3
    }
}
