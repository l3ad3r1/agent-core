package com.hermes.agent.data.plugin.evolution

import com.hermes.agent.data.plugin.ScriptPluginRepository
import com.hermes.agent.data.plugin.script.ScriptModuleDigest
import com.hermes.agent.data.plugin.script.ScriptPluginManifest
import com.hermes.agent.data.plugin.script.ScriptPluginPermissions
import java.util.UUID

/**
 * Delivers an approved evolution module on the fly: validate on the phone,
 * show the user, install locally with the approved bytes pinned, hot-reload.
 * No reinstall and no restart — [ScriptPluginRepository.installLocal] republishes
 * tools immediately and the [ToolOverrideController] rewires overrides after it.
 *
 * Keeps every installed version so the live one can be rolled back in one step,
 * and implements the automatic revert when an override keeps failing.
 */
class EvolutionModuleInstaller(
    private val store: EvolutionProposalStore,
    private val versions: EvolutionModuleVersionStore,
    private val repository: ScriptPluginRepository,
    private val vetter: EvolutionModuleVetter,
    private val smokeRunner: ModuleSmokeTestRunner,
    private val events: EvolutionEvents,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : OverrideRevertListener {

    /** Everything the user sees before tapping Install. */
    data class ModuleReview(
        val proposal: EvolutionProposal,
        val manifest: ScriptPluginManifest?,
        val manifestJson: String,
        val sha256: String,
        /** permission → plain-language description. */
        val permissions: List<Pair<String, String>>,
        val passed: Boolean,
        val findings: List<String>,
        val testReport: String,
    )

    /**
     * Re-runs the full on-device validation and smoke tests on the stored bytes.
     * The bots' earlier pass is not trusted: this is what the user's decision rests on.
     */
    suspend fun review(proposalId: String): Result<ModuleReview> = runCatching {
        val p = store.get(proposalId) ?: error("Proposal not found")
        require(p.kind.hotLoadable) { "Only module proposals can be installed on the phone" }
        val json = p.artifact ?: error("No module has been built for this proposal yet")
        val vet = vetter.vet(json, p.kind, p.targetTool)
        val manifest = vet.manifest
        if (!vet.ok || manifest == null) {
            return@runCatching ModuleReview(p, null, json, vet.sha256, emptyList(), false, vet.findings, "Validation failed")
        }
        val smoke = smokeRunner.run(manifest, vet.tests, manifest.permissions.toSet())
        ModuleReview(
            proposal = p,
            manifest = manifest,
            manifestJson = json,
            sha256 = vet.sha256,
            permissions = manifest.permissions.map { it to ScriptPluginPermissions.describe(it, manifest.hosts) },
            passed = smoke.passed && vet.sha256 == p.artifactSha256,
            findings = if (vet.sha256 == p.artifactSha256) emptyList() else listOf("Module bytes changed since review"),
            testReport = smoke.summary(),
        )
    }

    /**
     * Installs the module the user approved. [approvedSha256] is the digest shown on
     * the review screen; bytes that no longer match it are refused.
     */
    suspend fun install(proposalId: String, approvedSha256: String): Result<EvolutionProposal> = runCatching {
        val review = review(proposalId).getOrThrow()
        val p = review.proposal
        require(p.status == ProposalStatus.READY) { "Only a proposal that is ready can be installed (it is ${p.status.label})" }
        require(ScriptModuleDigest.check(approvedSha256, review.manifestJson) is ScriptModuleDigest.Result.Match) {
            "The module changed after you reviewed it; review it again"
        }
        val manifest = review.manifest
        check(review.passed && manifest != null) {
            "On-device checks failed: ${(review.findings + review.testReport).joinToString("; ").take(300)}"
        }

        val granted = manifest.permissions.toSet()
        repository.installLocal(
            manifestJson = review.manifestJson,
            approvedPermissions = granted,
            source = ScriptPluginRepository.SOURCE_EVOLUTION,
            expectedSha256 = approvedSha256,
        ).getOrElse { err ->
            store.transition(p.id, ProposalStatus.FAILED, "Install failed: ${err.message?.take(200)}", clock())
            throw err
        }

        val superseded = versions.versions(manifest.id).lastOrNull { it.active }
        val version = EvolutionModuleVersion(
            id = newId(),
            moduleId = manifest.id,
            proposalId = p.id,
            version = manifest.version,
            manifestJson = review.manifestJson,
            sha256 = review.sha256,
            grantedPermissions = granted,
            installedAt = clock(),
            active = true,
        )
        versions.record(version)
        versions.markActive(manifest.id, version.id)
        superseded?.proposalId?.takeIf { it != p.id }?.let { oldId ->
            store.get(oldId)?.let { old ->
                store.update(old.copy(statusMessage = "Superseded by v${manifest.version} (${p.title.take(60)})", updatedAt = clock()))
            }
        }
        store.transition(
            p.id, ProposalStatus.INSTALLED,
            "Installed v${manifest.version} — live now, no restart needed" +
                if (manifest.overrides.isNotEmpty()) ". Replaces ${manifest.overrides.joinToString()}; reverts automatically if it keeps failing." else ".",
            clock(),
        ) { it.copy(moduleId = manifest.id) } ?: error("The proposal changed during install")
    }

    /**
     * One-tap rollback of the live version: reinstalls the previous version (its own
     * pinned bytes and grants), or removes the module when there is none, which also
     * restores any built-in it overrode.
     */
    suspend fun rollback(proposalId: String): Result<EvolutionProposal> = runCatching {
        val p = store.get(proposalId) ?: error("Proposal not found")
        require(p.status == ProposalStatus.INSTALLED) { "Only an installed proposal can be rolled back" }
        val moduleId = p.moduleId ?: error("This proposal has no installed module")
        val history = versions.versions(moduleId).sortedBy { it.installedAt }
        val current = history.lastOrNull { it.active }
        require(current == null || current.proposalId == p.id) {
            "A newer version of this module is live; roll that one back first"
        }
        val previous = current?.let { cur -> history.lastOrNull { it.installedAt < cur.installedAt && it.id != cur.id } }

        val message = if (previous != null) {
            repository.installLocal(
                manifestJson = previous.manifestJson,
                approvedPermissions = previous.grantedPermissions,
                source = ScriptPluginRepository.SOURCE_EVOLUTION,
                expectedSha256 = previous.sha256,
            ).getOrThrow()
            versions.markActive(moduleId, previous.id)
            store.get(previous.proposalId)?.takeIf { it.id != p.id }?.let { old ->
                store.update(old.copy(statusMessage = "Restored by rollback", updatedAt = clock()))
            }
            "Rolled back to v${previous.version}"
        } else {
            repository.uninstall(moduleId)
            versions.markActive(moduleId, null)
            "Removed the module; the built-in behaviour is back"
        }
        store.transition(p.id, ProposalStatus.ROLLED_BACK, message, clock()) ?: error("The proposal changed during rollback")
    }

    /** An override kept failing: switch its module off and tell the user. The built-in is already back. */
    override suspend fun onOverrideTripped(moduleId: String, toolName: String, reason: String) {
        repository.setEnabled(moduleId, false)
        versions.markActive(moduleId, null)
        store.findInstalledByModule(moduleId)?.let { p ->
            store.transition(
                p.id, ProposalStatus.ROLLED_BACK,
                "Automatically reverted to the built-in '$toolName': ${reason.take(200)}", clock(),
            )
        }
        events.overrideReverted(toolName, moduleId, reason)
    }

    /** Files an app change (with the reviewed spec when there is one) to the self-repair repo. */
    suspend fun fileAppChange(proposalId: String, filer: AppChangeFiler): Result<EvolutionProposal> = runCatching {
        val p = store.get(proposalId) ?: error("Proposal not found")
        require(p.kind == ProposalKind.APP_CHANGE) { "Only an app change is filed to the repair repo" }
        require(filer.isConfigured) { "Set up self-repair reports first (Settings → Advanced → Self-repair)" }
        require(p.issueUrl == null) { "Already filed: ${p.issueUrl}" }
        val withSpec = p.status == ProposalStatus.READY && p.reviewVerdict == Verdict.APPROVE
        require(withSpec || p.status == ProposalStatus.APPROVED) {
            "File an app change once it is approved, or after the reviewer approved its spec"
        }
        val verdict = if (withSpec) ReviewVerdict(Verdict.APPROVE, p.reviewFindings) else null
        val url = filer.file(p, if (withSpec) p.artifact else null, verdict).getOrThrow()
        val message = "Filed $url. The fix arrives as a draft PR and ships through the test-build update " +
            "channel; mark it installed once you have that build."
        if (withSpec) {
            store.get(p.id)!!.let { store.update(it.copy(issueUrl = url, statusMessage = message, updatedAt = clock())) }
            store.get(p.id)!!
        } else {
            store.transition(p.id, ProposalStatus.READY, message, clock()) { it.copy(issueUrl = url) }
                ?: error("The proposal changed while filing")
        }
    }

    /** The user installed the test build that carries an app change. */
    suspend fun markAppChangeInstalled(proposalId: String): Result<EvolutionProposal> = runCatching {
        val p = store.get(proposalId) ?: error("Proposal not found")
        require(p.kind == ProposalKind.APP_CHANGE && p.issueUrl != null) { "Only a filed app change can be marked installed" }
        store.transition(p.id, ProposalStatus.INSTALLED, "Marked installed from the test build", clock())
            ?: error("Only a proposal that is ready can be marked installed")
    }
}
