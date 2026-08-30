package com.hermes.agent.data.terminal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import com.hermes.agent.domain.product.ProductIdentity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Runs commands in the user's separately-installed **Termux** app via its
 * `RUN_COMMAND` intent, returning stdout/stderr/exit-code through a result
 * PendingIntent. This gives the agent the full Termux Linux environment
 * (apt/pkg, python, git, ...) via the real Termux app, rather than the device's
 * limited `/system/bin/sh`.
 *
 * Requires: Termux installed, the `com.termux.permission.RUN_COMMAND` permission
 * (declared in the manifest), and `allow-external-apps=true` in the user's
 * `~/.termux/termux.properties`.
 */
@Singleton
class TermuxCommandRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val productIdentity: ProductIdentity,
) {
    fun isTermuxInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0); true
    }.getOrDefault(false)

    /** The runtime permission Termux requires callers to hold (it's `dangerous`). */
    val runCommandPermission: String get() = PERMISSION_RUN_COMMAND

    /**
     * Launches [command] in a **foreground** Termux session (opens Termux and
     * shows it running) -- fire-and-forget, for long/interactive flows like the
     * product installer or starting the agent. Returns null on success, or a
     * human-readable error explaining what to fix.
     */
    fun launchSession(command: String): String? {
        if (!isTermuxInstalled()) {
            return "Termux is not installed. Install Termux from F-Droid (not the Play Store build)."
        }
        val service = Intent().apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            action = ACTION_RUN_COMMAND
            putExtra(EXTRA_COMMAND_PATH, "$TERMUX_PREFIX/bin/bash")
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
            putExtra(EXTRA_WORKDIR, "$TERMUX_FILES/home")
            putExtra(EXTRA_BACKGROUND, false) // foreground: visible Termux session
            putExtra(EXTRA_SESSION_ACTION, "0") // open Termux & switch to new session
            putExtra(EXTRA_COMMAND_LABEL, productIdentity.displayName)
        }
        return try {
            ContextCompat.startForegroundService(context, service)
            null
        } catch (t: Throwable) {
            Timber.tag("Termux").w(t, "launchSession failed")
            "Couldn't reach Termux (${t.javaClass.simpleName}: ${t.message}). " +
                "Grant the \"Run commands in Termux\" permission, and set " +
                "allow-external-apps=true in ~/.termux/termux.properties."
        }
    }

    suspend fun run(command: String, timeoutMs: Long = 60_000): String {
        if (!isTermuxInstalled()) {
            return "Termux is not installed. Install it from F-Droid/GitHub to use this tool."
        }

        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String> { cont ->
                val action = newResultAction()
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        runCatching { context.unregisterReceiver(this) }
                        val bundle = intent?.getBundleExtra(EXTRA_RESULT_BUNDLE)
                        val stdout = bundle?.getString(RESULT_STDOUT).orEmpty().trim()
                        val stderr = bundle?.getString(RESULT_STDERR).orEmpty().trim()
                        val exit = bundle?.getInt(RESULT_EXIT_CODE, -1) ?: -1
                        val err = bundle?.getInt(RESULT_ERR, 0) ?: 0
                        val errmsg = bundle?.getString(RESULT_ERRMSG).orEmpty().trim()
                        Timber.tag("Termux").d("RUN_COMMAND result: stdout=$stdout exit=$exit err=$err errmsg=$errmsg")
                        if (cont.isActive) cont.resume(format(stdout, stderr, exit, err, errmsg))
                    }
                }
                // EXPORTED is required, not lax: Termux fills in and sends this
                // PendingIntent from its own UID, and from Android 14 a
                // dynamically registered NOT_EXPORTED receiver silently drops
                // cross-UID broadcasts -- which is why results never arrived.
                // The action carries a fresh random UUID per call, the receiver
                // lives only for that one command, and the PendingIntent is
                // package-scoped, so there is nothing here for another app to
                // reach without first guessing a v4 UUID inside the timeout.
                ContextCompat.registerReceiver(
                    context, receiver, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED,
                )
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

                val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pendingIntent = PendingIntent.getBroadcast(
                    context, 0, Intent(action).setPackage(context.packageName), piFlags,
                )

                val service = Intent().apply {
                    setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                    this.action = ACTION_RUN_COMMAND
                    putExtra(EXTRA_COMMAND_PATH, "$TERMUX_PREFIX/bin/bash")
                    putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
                    putExtra(EXTRA_WORKDIR, "$TERMUX_FILES/home")
                    putExtra(EXTRA_BACKGROUND, true)
                    putExtra(EXTRA_PENDING_INTENT, pendingIntent)
                    putExtra(EXTRA_COMMAND_LABEL, productIdentity.displayName)
                }

                try {
                    ContextCompat.startForegroundService(context, service)
                } catch (t: Throwable) {
                    Timber.tag("Termux").w(t, "RUN_COMMAND start failed")
                    runCatching { context.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(
                        "Failed to reach Termux: ${t.message ?: t.javaClass.simpleName}. " +
                            "Ensure Termux is installed and `allow-external-apps=true` is set in " +
                            "~/.termux/termux.properties.",
                    )
                }
            }
        }
        return result ?: "Termux command timed out after ${timeoutMs}ms."
    }

    /**
     * Renders one Termux result into the text the agent sees.
     *
     * `err` alone is not a reliable failure signal. Termux reports success as
     * `err=0` but has also been observed returning `err=-1` on a successful run,
     * and testing `err != 0` made every such run look like a plugin failure.
     * Testing `err > 0` instead would have the opposite bug: a genuine failure
     * reported with a negative code would be read as success and the model would
     * be handed an empty result as though the command had worked.
     *
     * So the signal used is the one Termux only populates when it actually
     * failed to run the command: a non-blank `errmsg`. That is independent of
     * whichever sign convention the installed Termux build uses. A non-zero
     * `err` with no message is logged and otherwise treated as success, so the
     * discrepancy stays visible without breaking the call.
     */
    internal fun format(stdout: String, stderr: String, exit: Int, err: Int, errmsg: String): String {
        // errmsg alone, deliberately: gating this on `err != 0` as well would
        // drop a genuine message whenever Termux reported it with err=0, which
        // is the same class of silent-success bug this check exists to prevent.
        if (errmsg.isNotBlank()) {
            return "Termux plugin error (err=$err): $errmsg. " +
                "Check that `allow-external-apps=true` is set in ~/.termux/termux.properties."
        }
        // A non-zero code with nothing to say is the observed err=-1-on-success
        // case. Logged so the anomaly stays visible, but not treated as failure.
        if (err != 0) {
            Timber.tag("Termux").w("non-zero err=%d with no errmsg; treating as success", err)
        }
        return buildString {
            append("exit_code=$exit")
            if (stdout.isNotEmpty()) append("\n").append(stdout)
            if (stderr.isNotEmpty()) append("\n[stderr]\n").append(stderr)
        }
    }

    /**
     * Constants are private except the two the exported-receiver safety
     * argument rests on, which a test has to be able to reach.
     */
    companion object {
        /**
         * A fresh, unguessable action for one command's result broadcast.
         *
         * The receiver must be registered EXPORTED for Termux to reach it at
         * all, so this per-call UUID is the only thing standing between the
         * receiver and a forged result from another app on the device. Exposed
         * so a test can assert it never repeats and never quietly degrades into
         * something predictable.
         */
        fun newResultAction(): String = "$RESULT_ACTION_PREFIX${UUID.randomUUID()}"

        const val RESULT_ACTION_PREFIX = "com.hermes.agent.TERMUX_RESULT."

        private const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_FILES = "/data/data/com.termux/files"
        private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        private const val EXTRA_RESULT_BUNDLE = "result"
        private const val RESULT_STDOUT = "stdout"
        private const val RESULT_STDERR = "stderr"
        private const val RESULT_EXIT_CODE = "exitCode"
        private const val RESULT_ERR = "err"
        private const val RESULT_ERRMSG = "errmsg"
    }
}

/**
 * Probe for a Hermes CLI installed inside Termux.
 *
 * `command -v` alone is not enough: the probe runs through a non-interactive
 * `bash -c`, which never sources the login profile that puts `$PREFIX/bin` and
 * `~/.local/bin` on PATH, so a perfectly good install was reported as missing.
 * The explicit `-x` checks cover both standard locations regardless of PATH.
 *
 * Shared rather than duplicated: both apps' chat screens run this, and a probe
 * that drifts between them means one product silently stops detecting an
 * install that the other finds.
 */
object HermesCliProbe {
    const val COMMAND: String =
        "(command -v hermes || [ -x /data/data/com.termux/files/usr/bin/hermes ] || " +
            "[ -x \"\$HOME/.local/bin/hermes\" ]) >/dev/null 2>&1 && " +
            "echo __HERMES_OK__ || echo __HERMES_NO__"

    const val PRESENT = "__HERMES_OK__"
    const val ABSENT = "__HERMES_NO__"

    /** Generous: the probe shells out through Termux, which may be cold-starting. */
    const val TIMEOUT_MS = 20_000L
}
