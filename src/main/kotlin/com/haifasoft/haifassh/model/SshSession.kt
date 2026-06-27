package com.haifasoft.haifassh.model

import com.intellij.util.xmlb.annotations.XCollection

/**
 * A saved SSH session. Secrets (password, key passphrase, jump-host passwords)
 * are NOT stored here — they live in the IDE [com.intellij.ide.passwordSafe.PasswordSafe]
 * keyed by this session's [id]. See com.haifasoft.haifassh.settings.SshCredentialStore.
 *
 * Mutable with defaults so the IntelliJ XML serializer can persist it.
 */
class SshSession {
    var id: String = ""
    var name: String = ""

    /** Empty means the session lives at the tree root. */
    var folderId: String = ""

    var host: String = ""
    var port: Int = 22
    var username: String = ""

    var authType: SshAuthType = SshAuthType.PASSWORD
    var privateKeyPath: String = ""

    /** When true, password / passphrase are persisted in PasswordSafe; otherwise prompted each connect. */
    var savePassword: Boolean = true

    @get:XCollection(style = XCollection.Style.v2)
    var jumpHosts: MutableList<JumpHost> = mutableListOf()

    fun displayName(): String = name.ifBlank { "$username@$host".trim('@') }.ifBlank { "Unnamed session" }

    fun copyOf(): SshSession {
        val c = SshSession()
        c.id = id
        c.name = name
        c.folderId = folderId
        c.host = host
        c.port = port
        c.username = username
        c.authType = authType
        c.privateKeyPath = privateKeyPath
        c.savePassword = savePassword
        c.jumpHosts = jumpHosts.map { it.copyOf() }.toMutableList()
        return c
    }
}
