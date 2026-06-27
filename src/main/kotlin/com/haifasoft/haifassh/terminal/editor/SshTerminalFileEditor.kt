package com.haifasoft.haifassh.terminal.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.jediterm.terminal.ui.JediTermWidget
import com.haifasoft.haifassh.terminal.SshTerminalUi
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent

/**
 * Hosts a live SSH terminal as an editor tab (the editor area where source files
 * open), rather than in a tool window. Owns the JediTerm widget and tears down
 * the widget + SSH connection when the tab is closed.
 */
class SshTerminalFileEditor(
    private val file: SshTerminalVirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val widget: JediTermWidget = SshTerminalUi.createWidget(file.session, file.handle, this)
    private val changeSupport = PropertyChangeSupport(this)

    override fun getComponent(): JComponent = widget

    override fun getPreferredFocusedComponent(): JComponent = widget.terminalPanel

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
        runCatching { widget.close() }
        runCatching { file.handle.close() }
    }
}
