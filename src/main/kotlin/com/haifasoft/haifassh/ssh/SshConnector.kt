package com.haifasoft.haifassh.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.haifasoft.haifassh.model.SshAuthType

/**
 * Opens interactive SSH shells, transparently chaining through any number of
 * jump hosts via nested local port forwarding (equivalent to OpenSSH ProxyJump).
 */
object SshConnector {

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val KEEPALIVE_INTERVAL_MS = 30_000

    /**
     * @param chain ordered hops; the last element is the target host, everything before it is a jump host.
     * @param termType PTY terminal type advertised to the remote (e.g. "xterm-256color").
     */
    fun openShell(chain: List<HopConfig>, termType: String, columns: Int, rows: Int): SshShellHandle {
        require(chain.isNotEmpty()) { "Connection chain must contain at least the target host" }

        val jsch = JSch()
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
                session.setConfig("StrictHostKeyChecking", "no")
                enableLegacyAlgorithms(session)
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
