package com.hermes.agent.domain.terminal

/**
 * A remote command-execution backend — the Android port of hermes-agent's
 * terminal environments (tools/environments/: local, Docker, SSH, Modal,
 * Daytona). On a phone the practical remote backend is SSH: the shell tool
 * gains a `target=remote` mode that runs commands on a configured host
 * instead of the on-device `sh` (and through SSH you also reach Docker —
 * `ssh host docker exec …`).
 */
interface RemoteTerminalBackend {

    data class Config(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        /**
         * The remote host's expected JSch fingerprint (for example
         * `aa:bb:...`). Connections fail before authentication when it is
         * absent or differs. This is deliberately separate from credentials.
         */
        val expectedHostFingerprint: String = "",
    ) {
        val isConfigured: Boolean
            get() = host.isNotBlank() && username.isNotBlank() && port in 1..65535 &&
                expectedHostFingerprint.isNotBlank()
    }

    data class ExecResult(
        val exitCode: Int,
        val output: String,
    )

    /**
     * Run [command] on the remote host. Implementations must not throw:
     * connection/auth failures come back as a failed [Result].
     */
    suspend fun execute(config: Config, command: String, timeoutMs: Long): Result<ExecResult>
}
