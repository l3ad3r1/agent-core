package com.hermes.agent.data.tools

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The subagents `delegate` is running right now, so the chat can show them and the user can steer
 * or stop one (upstream hermes-agent: "see and steer your subagents live").
 *
 * [DelegateTool] reports each subagent's task, start time and a short rolling transcript here. A
 * run leaves the list when it finishes. [stop] cancels one subagent, and `delegate` hands its
 * partial work back to the parent turn; [steer] queues a note the subagent reads before its next step.
 */
@Singleton
class SubagentMonitor @Inject constructor() {

    data class Run(
        val id: String,
        val goal: String,
        val startedAtMillis: Long,
        /** The latest activity, oldest first: tool calls, results and replies, each one line. */
        val lines: List<String> = emptyList(),
        val stopping: Boolean = false,
    )

    /** Thrown into a subagent the user stopped, so it can tell that apart from the turn ending. */
    class StoppedByUser : CancellationException("stopped by the user")

    private val _runs = MutableStateFlow<List<Run>>(emptyList())
    val runs: StateFlow<List<Run>> = _runs.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val steering = ConcurrentHashMap<String, MutableList<String>>()

    internal fun start(goal: String, now: Long = System.currentTimeMillis()): String {
        val id = UUID.randomUUID().toString()
        _runs.update { it + Run(id, goal, now) }
        return id
    }

    internal fun attach(id: String, job: Job) { jobs[id] = job }

    internal fun log(id: String, line: String) {
        val clean = line.replace('\n', ' ').trim().take(MAX_LINE_CHARS)
        if (clean.isEmpty()) return
        _runs.update { list -> list.map { if (it.id == id) it.copy(lines = (it.lines + clean).takeLast(MAX_LINES)) else it } }
    }

    internal fun finish(id: String) {
        jobs.remove(id)
        steering.remove(id)
        _runs.update { list -> list.filterNot { it.id == id } }
    }

    /** Notes the user sent this subagent since it last looked, oldest first. */
    internal fun takeSteering(id: String): List<String> =
        steering.remove(id)?.let { synchronized(it) { it.toList() } }.orEmpty()

    /** Queue [note] for subagent [id]; false when it is no longer running. */
    fun steer(id: String, note: String): Boolean {
        val text = note.trim()
        if (text.isEmpty() || _runs.value.none { it.id == id }) return false
        val queue = steering.getOrPut(id) { mutableListOf() }
        synchronized(queue) { queue += text }
        log(id, "you: $text")
        return true
    }

    /** Stop subagent [id]; its partial work goes back to the parent. False when it is not running. */
    fun stop(id: String): Boolean {
        val job = jobs[id] ?: return false
        _runs.update { list -> list.map { if (it.id == id) it.copy(stopping = true) else it } }
        job.cancel(StoppedByUser())
        return true
    }

    private companion object {
        const val MAX_LINES = 12
        const val MAX_LINE_CHARS = 200
    }
}
