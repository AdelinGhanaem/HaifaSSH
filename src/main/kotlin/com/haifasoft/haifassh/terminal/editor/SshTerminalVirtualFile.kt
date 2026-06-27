package com.haifasoft.haifassh.terminal.editor

import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.testFramework.LightVirtualFile
import com.haifasoft.haifassh.model.SshSession
import com.haifasoft.haifassh.ssh.SshShellHandle

/**
 * An in-memory virtual file that represents one live SSH connection, so the
 * terminal can be opened as an editor tab via [com.intellij.openapi.fileEditor.FileEditorManager].
 *
 * Carries the already-opened [handle]; [SshTerminalFileEditorProvider] reads it
 * when the platform asks it to build the editor. Each connection gets its own
 * instance (identity equality) so repeated connects open separate tabs.
 */
class SshTerminalVirtualFile(
    val session: SshSession,
    val handle: SshShellHandle,
) : LightVirtualFile(session.displayName(), FileTypes.UNKNOWN, "") {

    init {
        isWritable = false
    }
}
