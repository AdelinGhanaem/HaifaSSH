package com.haifasoft.haifassh.terminal.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.haifasoft.haifassh.terminal.assist.SshTerminalPane
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent

/**
 * Hosts a live SSH terminal as an editor tab (the editor area where source files
 * open), rather than in a tool window.
 *
 * The terminal pane itself is owned by [SshTerminalVirtualFile], NOT by this
 * editor: the platform disposes and re-creates FileEditors when a tab is dragged
 * to another split or window, and owning the widget here would wipe the terminal
 * text and kill the SSH connection on every re-arrange. This editor only
 * presents the file's pane; teardown happens when the file is closed everywhere.
 */
class SshTerminalFileEditor(
    private val project: Project,
    private val file: SshTerminalVirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val pane: SshTerminalPane = file.acquirePane(project)
    private val changeSupport = PropertyChangeSupport(this)

    override fun getComponent(): JComponent = pane.component

    override fun getPreferredFocusedComponent(): JComponent = pane.preferredFocus

    override fun getName(): String = file.session.displayName()

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) { /* terminals have no persisted UI state */ }

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun getCurrentLocation() = null

    override fun getState(level: FileEditorStateLevel): FileEditorState =
        FileEditorState.INSTANCE

    override fun addPropertyChangeListener(listener: PropertyChangeListener) =
        changeSupport.addPropertyChangeListener(listener)

    override fun removePropertyChangeListener(listener: PropertyChangeListener) =
        changeSupport.removePropertyChangeListener(listener)

    override fun dispose() {
        // Disposal happens both on real close AND mid-drag (move to another split
        // recreates the editor for the same file). Defer, then only tear the
        // terminal down if the file is no longer open anywhere in the project.
        ApplicationManager.getApplication().invokeLater {
            val stillOpen = !project.isDisposed &&
                runCatching { FileEditorManager.getInstance(project).isFileOpen(file) }.getOrDefault(false)
            if (!stillOpen) file.tearDown()
        }
    }
}
