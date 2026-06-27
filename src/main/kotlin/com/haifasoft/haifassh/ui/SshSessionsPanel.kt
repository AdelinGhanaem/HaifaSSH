package com.haifasoft.haifassh.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.haifasoft.haifassh.model.SshFolder
import com.haifasoft.haifassh.model.SshSession
import com.haifasoft.haifassh.settings.HaifaSshSettings
import com.haifasoft.haifassh.settings.SshSessionService
import com.haifasoft.haifassh.settings.SshSessionTransfer
import com.haifasoft.haifassh.terminal.SshTerminalManager
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The session manager UI: a folder/session tree with a toolbar to create,
 * edit, delete and connect sessions. Mirrors MobaXterm's left-hand session list.
 */
class SshSessionsPanel(
    private val project: Project,
    parentDisposable: Disposable,
) : JPanel(BorderLayout()) {

    private val service = SshSessionService.getInstance()
    private val rootNode = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = SessionTreeCellRenderer()
        tree.emptyText.text = "No SSH sessions yet — click + to add one"

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (selectedSession() != null) {
                    connectSelected()
                    return true
                }
                return false
            }
        }.installOn(tree)

        val decorator = ToolbarDecorator.createDecorator(tree)
            .setAddAction { addSession() }
            .setAddActionName("New Session")
            .setEditAction { editSelected() }
            .setRemoveAction { removeSelected() }
            .addExtraAction(ConnectAction())
            .addExtraAction(NewFolderAction())
            .addExtraAction(ImportAction())
            .addExtraAction(ExportAction())
            .addExtraAction(OpenInEditorToggleAction())
            .addExtraAction(TerminalColorsAction())
            .setPreferredSize(JBUI.size(280, 400))

        border = BorderFactory.createEmptyBorder()
        add(decorator.createPanel(), BorderLayout.CENTER)

        // Rebuild whenever the stored sessions change (e.g. from another window).
        ApplicationManager.getApplication().messageBus.connect(parentDisposable)
            .subscribe(SshSessionService.TOPIC, SshSessionService.Listener { reload() })

        reload()
    }

    // ---- Tree building -----------------------------------------------------

    private fun reload() {
        rootNode.removeAllChildren()
        appendChildren(rootNode, "")
        treeModel.reload()
        expandAll()
    }

    private fun appendChildren(parent: DefaultMutableTreeNode, folderId: String) {
        for (folder in service.foldersIn(folderId)) {
            val node = DefaultMutableTreeNode(folder)
            parent.add(node)
            appendChildren(node, folder.id)
        }
        for (session in service.sessionsIn(folderId)) {
            parent.add(DefaultMutableTreeNode(session))
        }
    }

    private fun expandAll() {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    // ---- Selection helpers -------------------------------------------------

    private fun selectedNode(): DefaultMutableTreeNode? =
        tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode

    private fun selectedSession(): SshSession? = selectedNode()?.userObject as? SshSession

    private fun selectedFolder(): SshFolder? = selectedNode()?.userObject as? SshFolder

    /** Folder a new item should be created in, based on the current selection. */
    private fun targetFolderId(): String = when (val obj = selectedNode()?.userObject) {
        is SshFolder -> obj.id
        is SshSession -> obj.folderId
        else -> ""
    }

    // ---- Actions -----------------------------------------------------------

    private fun addSession() {
        val draft = SshSession().apply { folderId = targetFolderId() }
        val dialog = SshSessionDialog(project, draft, isNew = true)
        if (dialog.showAndGet()) {
            val saved = dialog.persist(service)
            selectSession(saved.id)
        }
    }

    private fun addFolder() {
        val name = Messages.showInputDialog(
            project,
            "Folder name:",
            "New Folder",
            null,
            "New Folder",
            null,
        )?.trim()
        if (!name.isNullOrEmpty()) {
            service.addFolder(name, targetFolderId())
        }
    }

    private fun editSelected() {
        selectedSession()?.let { session ->
            val dialog = SshSessionDialog(project, session.copyOf(), isNew = false)
            if (dialog.showAndGet()) {
                val saved = dialog.persist(service)
                selectSession(saved.id)
            }
            return
        }
        selectedFolder()?.let { folder ->
            val name = Messages.showInputDialog(
                project,
                "Folder name:",
                "Rename Folder",
                null,
                folder.name,
                null,
            )?.trim()
            if (!name.isNullOrEmpty()) {
                service.renameFolder(folder.id, name)
            }
        }
    }

    private fun removeSelected() {
        selectedSession()?.let { session ->
            val confirm = Messages.showYesNoDialog(
                project,
                "Delete session \"${session.displayName()}\"? Its saved credentials will also be removed.",
                "Delete Session",
                Messages.getQuestionIcon(),
            )
            if (confirm == Messages.YES) service.deleteSession(session.id)
            return
        }
        selectedFolder()?.let { folder ->
            val confirm = Messages.showYesNoDialog(
                project,
                "Delete folder \"${folder.name}\" and everything inside it?",
                "Delete Folder",
                Messages.getQuestionIcon(),
            )
            if (confirm == Messages.YES) service.deleteFolder(folder.id)
        }
    }

    private fun connectSelected() {
        selectedSession()?.let { SshTerminalManager.getInstance(project).connect(it) }
    }

    private fun exportSessions() {
        val count = service.sessions().size
        if (count == 0) {
            Messages.showInfoMessage(project, "There are no sessions to export.", "Export SSH Sessions")
            return
        }
        val options = ExportOptionsDialog(project, count)
        if (!options.showAndGet()) return

        val descriptor = FileSaverDescriptor("Export SSH Sessions", "Choose where to save the export file", "xml")
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as java.nio.file.Path?, "haifassh-sessions.xml")
            ?: return

        try {
            SshSessionTransfer.export(wrapper.file.toPath(), options.includeSecrets)
            Messages.showInfoMessage(
                project,
                "Exported $count session(s) to:\n${wrapper.file.path}",
                "Export Complete",
            )
        } catch (t: Throwable) {
            Messages.showErrorDialog(
                project,
                "Failed to export sessions: ${t.message ?: t.javaClass.simpleName}",
                "Export Failed",
            )
        }
    }

    private fun importSessions() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("xml")
            .withTitle("Import SSH Sessions")
            .withDescription("Select a HaifaSSH export file")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return

        try {
            val result = SshSessionTransfer.import(file.toNioPath())
            val secretsNote = if (result.secretsRestored) " Credentials were restored." else ""
            Messages.showInfoMessage(
                project,
                "Imported ${result.sessions} session(s) and ${result.folders} folder(s).$secretsNote",
                "Import Complete",
            )
        } catch (t: Throwable) {
            Messages.showErrorDialog(
                project,
                "Failed to import sessions: ${t.message ?: t.javaClass.simpleName}",
                "Import Failed",
            )
        }
    }

    private fun selectSession(id: String) {
        val node = findSessionNode(rootNode, id) ?: return
        val path = javax.swing.tree.TreePath(node.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun findSessionNode(parent: DefaultMutableTreeNode, id: String): DefaultMutableTreeNode? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) as DefaultMutableTreeNode
            val obj = child.userObject
            if (obj is SshSession && obj.id == id) return child
            findSessionNode(child, id)?.let { return it }
        }
        return null
    }

    // ---- Toolbar actions ---------------------------------------------------

    private inner class ConnectAction : DumbAwareAction(
        "Connect",
        "Open an SSH terminal for the selected session",
        AllIcons.Actions.Execute,
    ) {
        override fun actionPerformed(e: AnActionEvent) = connectSelected()

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedSession() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class NewFolderAction : DumbAwareAction(
        "New Folder",
        "Create a folder",
        AllIcons.Actions.NewFolder,
    ) {
        override fun actionPerformed(e: AnActionEvent) = addFolder()

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class ImportAction : DumbAwareAction(
        "Import Sessions…",
        "Import SSH sessions from a file",
        AllIcons.ToolbarDecorator.Import,
    ) {
        override fun actionPerformed(e: AnActionEvent) = importSessions()

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class ExportAction : DumbAwareAction(
        "Export Sessions…",
        "Export all SSH sessions to a file",
        AllIcons.ToolbarDecorator.Export,
    ) {
        override fun actionPerformed(e: AnActionEvent) = exportSessions()

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.sessions().isNotEmpty()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class TerminalColorsAction : DumbAwareAction(
        "Terminal Colors…",
        "Choose the terminal color scheme or customize colors",
        AllIcons.General.Settings,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, TerminalAppearanceConfigurable::class.java)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class OpenInEditorToggleAction : ToggleAction(
        "Open Terminals in Editor Tab",
        "When enabled, connecting opens the terminal as an editor tab; otherwise in the bottom tool window",
        AllIcons.Actions.MoveToWindow,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = HaifaSshSettings.getInstance().openInEditor

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            HaifaSshSettings.getInstance().openInEditor = state
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    // ---- Renderer ----------------------------------------------------------

    private class SessionTreeCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val obj = (value as? DefaultMutableTreeNode)?.userObject
            when (obj) {
                is SshFolder -> {
                    icon = AllIcons.Nodes.Folder
                    append(obj.name)
                }

                is SshSession -> {
                    icon = HaifaIcons.Session
                    append(obj.displayName())
                    val target = buildString {
                        append("  ")
                        if (obj.username.isNotBlank()) append("${obj.username}@")
                        append(obj.host.ifBlank { "?" })
                        append(":${obj.port}")
                        if (obj.jumpHosts.isNotEmpty()) append(" via ${obj.jumpHosts.size} jump")
                    }
                    append(target, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }

                else -> append(obj?.toString() ?: "")
            }
        }
    }
}
