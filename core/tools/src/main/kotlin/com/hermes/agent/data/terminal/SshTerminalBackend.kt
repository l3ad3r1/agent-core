package com.hermes.agent.data.terminal

import com.hermes.agent.domain.terminal.RemoteTerminalBackend
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSH implementation of [RemoteTerminalBackend] using JSch (mwiede fork,
 * pure-Java — no native libs, works on Android).
 *
 * Password auth against the configured host. The caller must supply a
 * fingerprint obtained out-of-band; the key is checked during SSH handshake,
 * before password authentication or command execution.
 *
 * The exec channel merges stderr into the output stream so the LLM sees
 * the same combined transcript the local shell tool produces.
 */
@Singleton
class SshTerminalBackend @Inject constructor() : RemoteTerminalBackend {

    override suspend fun execute(
        config: RemoteTerminalBackend.Config,
        command: String,
        timeoutMs: Long,
    ): Result<RemoteTerminalBackend.ExecResult> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result.failure(
                IllegalStateException("remote shell is not configured (host, user, or trusted host fingerprint missing)"),
            )
        }
        var session: Session? = null
        var channel: ChannelExec? = null
        runCatching {
            val jsch = JSch().apply {
                hostKeyRepository = FingerprintHostKeyRepository(config.host, config.expectedHostFingerprint, this)
            }
            session = jsch.getSession(config.username, config.host, config.port).apply {
                setPassword(config.password)
                setConfig("StrictHostKeyChecking", "yes")
                timeout = CONNECT_TIMEOUT_MS
                connect(CONNECT_TIMEOUT_MS)
            }

            channel = (session!!.openChannel("exec") as ChannelExec).apply {
                setCommand(command)
                inputStream = null
                setErrStream(null) // merged below
            }
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            channel!!.outputStream = null
            val stdout = channel!!.inputStream
            channel!!.setErrStream(err, true)
            channel!!.connect(CONNECT_TIMEOUT_MS)

            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                while (stdout.available() > 0) {
                    val n = stdout.read(buf, 0, minOf(stdout.available(), buf.size))
                    if (n > 0) out.write(buf, 0, n)
                }
                if (channel!!.isClosed) break
                Thread.sleep(50)
            }
            // Drain what's left after close/timeout.
            while (stdout.available() > 0) {
                val n = stdout.read(buf, 0, minOf(stdout.available(), buf.size))
                if (n > 0) out.write(buf, 0, n)
            }

            if (!channel!!.isClosed) {
                throw IllegalStateException("remote command timed out after ${timeoutMs / 1000}s")
            }

            val combined = buildString {
                append(out.toByteArray().toString(Charsets.UTF_8))
                val e = err.toByteArray().toString(Charsets.UTF_8)
                if (e.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(e)
                }
            }.trim()

            RemoteTerminalBackend.ExecResult(
                exitCode = channel!!.exitStatus,
                output = combined,
            )
        }.onFailure {
            Timber.tag("SshBackend").w(it, "ssh exec failed against %s:%d", config.host, config.port)
        }.also {
            runCatching { channel?.disconnect() }
            runCatching { session?.disconnect() }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
    }

    /** A one-host, one-fingerprint known-hosts repository. */
    private class FingerprintHostKeyRepository(
        private val expectedHost: String,
        expectedFingerprint: String,
        private val jsch: JSch,
    ) : HostKeyRepository {
        private val expected = normalize(expectedFingerprint)

        override fun check(host: String, key: ByteArray): Int {
            if (host != expectedHost) return HostKeyRepository.NOT_INCLUDED
            val actual = normalize(HostKey(host, key).getFingerPrint(jsch))
            return if (actual == expected) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }

        override fun add(hostkey: HostKey?, ui: com.jcraft.jsch.UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "pinned:$expectedHost"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

        private companion object {
            fun normalize(value: String): String = value.trim().lowercase().replace("-", ":")
        }
    }
}
