package com.haifasoft.haifassh.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.haifasoft.haifassh.model.JumpHost
import com.haifasoft.haifassh.model.SshFolder
import com.haifasoft.haifassh.model.SshSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Exports the SSH session tree to a portable XML file and imports it back,
 * optionally carrying credentials. Import always assigns fresh ids and remaps
 * folder references, so importing never overwrites existing sessions — it merges.
 */
object SshSessionTransfer {

    /** Result of an import, for user feedback. */
    data class ImportResult(val folders: Int, val sessions: Int, val secretsRestored: Boolean)

    // ---- Export ------------------------------------------------------------

    fun export(target: Path, includeSecrets: Boolean) {
        val service = SshSessionService.getInstance()
        val bundle = SshExportBundle().apply {
            this.includesSecrets = includeSecrets
            folders = service.folders().map { it.copyForExport() }.toMutableList()
            sessions = service.sessions().map { it.toExport(includeSecrets) }.toMutableList()
        }
        val element = XmlSerializer.serialize(bundle)
        Files.writeString(target, JDOMUtil.write(element))
    }

    private fun SshFolder.copyForExport(): SshFolder = SshFolder().also {
        it.id = id
        it.name = name
        it.parentId = parentId
    }

    private fun SshSession.toExport(includeSecrets: Boolean): SshSessionExport {
        val store = SshCredentialStore
        return SshSessionExport().also { e ->
            e.id = id
            e.name = name
            e.folderId = folderId
            e.host = host
            e.port = port
            e.username = username
            e.authType = authType
            e.privateKeyPath = privateKeyPath
            e.savePassword = savePassword
            e.jumpHosts = jumpHosts.mapIndexed { index, jump ->
                JumpHostExport().also { j ->
                    j.host = jump.host
                    j.port = jump.port
                    j.username = jump.username
                    j.authType = jump.authType
                    j.privateKeyPath = jump.privateKeyPath
                    if (includeSecrets) {
                        j.password = store.getJumpPassword(id, index)
                        j.passphrase = store.getJumpPassphrase(id, index)
                    }
                }
            }.toMutableList()
            if (includeSecrets) {
                e.password = store.getPassword(id)
                e.passphrase = store.getPassphrase(id)
            }
        }
    }

    // ---- Import ------------------------------------------------------------

    fun import(source: Path): ImportResult {
        val element = JDOMUtil.load(source)
        val bundle = XmlSerializer.deserialize(element, SshExportBundle::class.java)

        // Fresh ids keep the import a merge — existing sessions are never clobbered.
        val folderIdMap = HashMap<String, String>()
        val newFolders = bundle.folders.map { f ->
            SshFolder().also {
                it.id = UUID.randomUUID().toString()
                it.name = f.name
                folderIdMap[f.id] = it.id
            }
        }
        // Remap parents now that every folder has a new id.
        bundle.folders.forEachIndexed { i, f ->
            newFolders[i].parentId = folderIdMap[f.parentId] ?: ""
        }

        val store = SshCredentialStore
        val newSessions = bundle.sessions.map { s ->
            val session = SshSession().also {
                it.id = UUID.randomUUID().toString()
                it.name = s.name
                it.folderId = folderIdMap[s.folderId] ?: ""
                it.host = s.host
                it.port = s.port
                it.username = s.username
                it.authType = s.authType
                it.privateKeyPath = s.privateKeyPath
                it.savePassword = s.savePassword
                it.jumpHosts = s.jumpHosts.map { j ->
                    JumpHost().also { h ->
                        h.host = j.host
                        h.port = j.port
                        h.username = j.username
                        h.authType = j.authType
                        h.privateKeyPath = j.privateKeyPath
                    }
                }.toMutableList()
            }
            if (bundle.includesSecrets) {
                store.setPassword(session.id, s.password)
                store.setPassphrase(session.id, s.passphrase)
                s.jumpHosts.forEachIndexed { index, j ->
                    store.setJumpPassword(session.id, index, j.password)
                    store.setJumpPassphrase(session.id, index, j.passphrase)
                }
            }
            session
        }

        SshSessionService.getInstance().importBundle(newFolders, newSessions)
        return ImportResult(newFolders.size, newSessions.size, bundle.includesSecrets)
    }
}
