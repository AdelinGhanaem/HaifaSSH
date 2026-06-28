package com.haifasoft.haifassh.ssh

import com.intellij.openapi.application.PathManager
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.haifasoft.haifassh.model.SshAuthType
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opens interactive SSH shells, transparently chaining through any number of
 * jump hosts via nested local port forwarding (equivalent to OpenSSH ProxyJump).
 *
 * Host keys are verified against a plugin-managed `known_hosts` file with
 * trust-on-first-use semantics ([HostKeyPrompt]); see [openShell].
 */
object SshConnector {

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val KEEPALIVE_INTERVAL_MS = 30_000

    /**
     * Asks the user whether to trust a host key. Implemented by the UI layer so
     * the prompt can run on the EDT while the connection runs in the background.
     */
    interface HostKeyPrompt {
        /**
         * @param message JSch's host-key text (host, key type, fingerprint, and —
         * for a *changed* key — the loud MITM warning). Return true to trust and
         * continue, false to abort the connection.
         */
        fun confirm(message: String): Boolean

        /** Informational warning shown before a confirm (e.g. the changed-key banner). */
        fun notify(message: String) {}
    }

    /**
     * @param chain ordered hops; the last element is the target host, everything before it is a jump host.
     * @param termType PTY terminal type advertised to the remote (e.g. "xterm-256color").
     * @param hostKeyPrompt consulted when a host key is unknown or has changed.
     */
    fun openShell(
        chain: List<HopConfig>,
        termType: String,
        columns: Int,
        rows: Int,
        hostKeyPrompt: HostKeyPrompt,
    ): SshShellHandle {
        require(chain.isNotEmpty()) { "Connection chain must contain at least the target host" }

        val jsch = JSch()
        jsch.setKnownHosts(knownHostsFile())
        val userInfo = HostKeyUserInfo(hostKeyPrompt)
        val opened = ArrayList<Session>(chain.size)
        try {
            var previous: Session? = null
            for (hop in chain) {
                // First hop is reached directly; later hops are reached through a local
                // forward opened on the previous hop's session.
                val (connectHost, connectPort) = if (previous == null) {
                    hop.host to hop.port
                } else {
                    val localPort = previous.setPortForwardingL("127.0.0.1", 0, hop.host, hop.port)
                    "127.0.0.1" to localPort
                }

                val session = jsch.getSession(hop.username, connectHost, connectPort)
                applyAuth(jsch, session, hop)
                // Verify against the hop's REAL identity, not the (possibly tunneled,
                // ephemeral-port) socket we actually connect to. JSch uses the alias
                // verbatim as the known_hosts key, so entries stay stable across jumps.
                session.hostKeyAlias = knownHostsAlias(hop.host, hop.port)
                session.userInfo = userInfo
                session.setConfig("StrictHostKeyChecking", "ask")
                if (hop.allowLegacy) enableLegacyAlgorithms(session)
                session.serverAliveInterval = KEEPALIVE_INTERVAL_MS
                session.connect(CONNECT_TIMEOUT_MS)
                opened.add(session)
                previous = session
            }

            val target = opened.last()
            val channel = target.openChannel("shell") as ChannelShell
            channel.setPtyType(termType)
            channel.setPtySize(columns, rows, 0, 0)
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(CONNECT_TIMEOUT_MS)

            return SshShellHandle(channel, input, output, opened)
        } catch (t: Throwable) {
            opened.asReversed().forEach { runCatching { it.disconnect() } }
            throw t
        }
    }

    /**
     * Re-enables the legacy SHA-1 host-key and public-key algorithms (`ssh-rsa`,
     * `ssh-dss`) that the JSch fork drops from its defaults. Older servers (and
     * appliances) often still only offer these, which otherwise causes
     * "Algorithm negotiation fail: server_host_key ... serverProposal=ssh-rsa,ssh-dss".
     *
     * Modern algorithms stay first in the list, so they're still preferred; the
     * legacy ones are only used as a fallback when the server offers nothing newer.
     */
    private fun enableLegacyAlgorithms(session: Session) {
        val legacy = listOf("ssh-rsa", "ssh-dss")
        session.setConfig("server_host_key", mergeAlgorithms(session.getConfig("server_host_key"), legacy))
        session.setConfig(
            "PubkeyAcceptedAlgorithms",
            mergeAlgorithms(session.getConfig("PubkeyAcceptedAlgorithms"), legacy),
        )
    }

    /** Appends [additions] not already present to a comma-separated algorithm list, preserving order. */
    private fun mergeAlgorithms(current: String?, additions: List<String>): String {
        val have = (current ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        for (algorithm in additions) if (algorithm !in have) have.add(algorithm)
        return have.joinToString(",")
    }

    /**
     * The plugin-managed `known_hosts` file under the IDE config directory. Kept
     * separate from `~/.ssh/known_hosts` so the plugin never rewrites the user's
     * system file. Created empty on first use so JSch can load and later sync it.
     */
    private fun knownHostsFile(): String {
        val dir = Path.of(PathManager.getConfigPath(), "haifassh")
        Files.createDirectories(dir)
        val file = dir.resolve("known_hosts")
        if (Files.notExists(file)) Files.createFile(file)
        return file.toString()
    }

    /** OpenSSH-style host identity: `host` for port 22, `[host]:port` otherwise. */
    private fun knownHostsAlias(host: String, port: Int): String =
        if (port == 22) host else "[$host]:$port"

    /**
     * Bridges JSch's host-key prompts to the UI [HostKeyPrompt]. Only host-key
     * decisions go through here; passwords/passphrases are supplied directly
     * (`setPassword` / `addIdentity`), so the credential methods are inert.
     */
    private class HostKeyUserInfo(private val prompt: HostKeyPrompt) : UserInfo {
        override fun getPassword(): String? = null
        override fun getPassphrase(): String? = null
        override fun promptPassword(message: String): Boolean = false
        override fun promptPassphrase(message: String): Boolean = false
        override fun promptYesNo(message: String): Boolean = prompt.confirm(message)
        override fun showMessage(message: String) = prompt.notify(message)
    }

    private fun applyAuth(jsch: JSch, session: Session, hop: HopConfig) {
        when (hop.authType) {
            SshAuthType.PASSWORD -> {
                hop.password?.let { session.setPassword(it) }
                session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
            }

            SshAuthType.KEY -> {
                require(hop.privateKeyPath.isNotBlank()) {
                    "Private key path is required for key authentication (${hop.host})"
                }
                if (hop.passphrase.isNullOrEmpty()) {
                    jsch.addIdentity(hop.privateKeyPath)
                } else {
                    jsch.addIdentity(hop.privateKeyPath, hop.passphrase)
                }
                session.setConfig("PreferredAuthentications", "publickey")
            }
        }
    }
}
