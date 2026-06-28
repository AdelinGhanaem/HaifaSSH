package com.haifasoft.haifassh.ssh

import com.haifasoft.haifassh.model.SshAuthType

/**
 * A single, fully-resolved hop in an SSH connection chain (secrets already
 * fetched from PasswordSafe). The last hop in the list is the target host;
 * any preceding hops are jump hosts.
 */
data class HopConfig(
    val host: String,
    val port: Int,
    val username: String,
    val authType: SshAuthType,
    val privateKeyPath: String,
    val password: String?,
    val passphrase: String?,
    /** Opt-in fallback to legacy SHA-1/DSA algorithms for this hop. */
    val allowLegacy: Boolean = false,
)
