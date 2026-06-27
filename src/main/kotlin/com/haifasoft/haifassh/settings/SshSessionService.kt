package com.haifasoft.haifassh.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import com.haifasoft.haifassh.model.SshFolder
import com.haifasoft.haifassh.model.SshSession
import java.util.UUID

/**
 * Application-level store of the SSH session tree, persisted to
 * <config>/options/haifassh-sessions.xml. Secrets are kept separately in
 * [SshCredentialStore]; only non-secret metadata lives here.
 */
@Service(Service.Level.APP)
@State(name = "HaifaSshSessions", storages = [Storage("haifassh-sessions.xml")])
class SshSessionService : PersistentStateComponent<SshSessionState> {

    private var state = SshSessionState()

    override fun getState(): SshSessionState = state

    override fun loadState(loaded: SshSessionState) {
        state = loaded
    }

    // ---- Read --------------------------------------------------------------

    fun folders(): List<SshFolder> = state.folders.toList()

    fun sessions(): List<SshSession> = state.sessions.toList()

    fun foldersIn(parentId: String): List<SshFolder> =
        state.folders.filter { it.parentId == parentId }.sortedBy { it.name.lowercase() }

    fun sessionsIn(folderId: String): List<SshSession> =
        state.sessions.filter { it.folderId == folderId }.sortedBy { it.displayName().lowercase() }

    fun findSession(id: String): SshSession? = state.sessions.firstOrNull { it.id == id }

    fun findFolder(id: String): SshFolder? = state.folders.firstOrNull { it.id == id }

    // ---- Write -------------------------------------------------------------

    fun addFolder(name: String, parentId: String): SshFolder {
        val folder = SshFolder().apply {
            id = UUID.randomUUID().toString()
            this.name = name
            this.parentId = parentId
        }
        state.folders.add(folder)
        fireChanged()
        return folder
    }

    fun renameFolder(id: String, name: String) {
        findFolder(id)?.let {
            it.name = name
            fireChanged()
        }
    }

    /** Upserts a session. Caller assigns [SshSession.id] (or leaves blank for a new one). */
    fun upsertSession(session: SshSession): SshSession {
        if (session.id.isBlank()) session.id = UUID.randomUUID().toString()
        val existing = state.sessions.indexOfFirst { it.id == session.id }
        if (existing >= 0) state.sessions[existing] = session else state.sessions.add(session)
        fireChanged()
        return session
    }

    /**
     * Adds pre-built folders and sessions in bulk (used by import). Callers are
     * responsible for assigning unique [SshFolder.id]/[SshSession.id] values and
     * remapping parent/folder references beforehand.
     */
    fun importBundle(newFolders: List<SshFolder>, newSessions: List<SshSession>) {
        state.folders.addAll(newFolders)
        state.sessions.addAll(newSessions)
        fireChanged()
    }

    fun deleteSession(id: String) {
        if (state.sessions.removeIf { it.id == id }) {
            SshCredentialStore.clearSession(id)
            fireChanged()
        }
    }

    /** Deletes a folder and everything (sub-folders + sessions) under it, recursively. */
    fun deleteFolder(id: String) {
        val toRemove = collectSubtree(id)
        state.folders.removeIf { it.id in toRemove }
        val removedSessions = state.sessions.filter { it.folderId in toRemove }
        removedSessions.forEach { SshCredentialStore.clearSession(it.id) }
        state.sessions.removeIf { it.folderId in toRemove }
        fireChanged()
    }

    private fun collectSubtree(rootId: String): Set<String> {
        val result = linkedSetOf(rootId)
        var frontier = listOf(rootId)
        while (frontier.isNotEmpty()) {
            val children = state.folders.filter { it.parentId in frontier }.map { it.id }
            val fresh = children.filter { result.add(it) }
            frontier = fresh
        }
        return result
    }

    private fun fireChanged() {
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .messageBus.syncPublisher(TOPIC).sessionsChanged()
    }

    fun interface Listener {
        fun sessionsChanged()
    }

    companion object {
        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("HaifaSSH sessions changed", Listener::class.java)

        fun getInstance(): SshSessionService = service()
    }
}
