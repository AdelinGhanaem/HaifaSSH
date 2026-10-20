package com.haifasoft.haifassh.fs.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.haifasoft.haifassh.model.SshSession
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.dnd.DnDConstants
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.TransferHandler
import javax.swing.WindowConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * A non-modal, standalone local file system explorer window.
 * Supports:
 * - Exploring local folders and drives
 * - Multiple file selection
 * - Drag and drop from this explorer directly into the remote session
 * - Direct upload button to the active session
 * - File details (Size, Type, Modified)
 */
class LocalFileExplorerWindow(
    private val project: Project,
    private val session: SshSession,
    private val onUploadFiles: (List<File>) -> Unit,
) : JDialog(
    WindowManager.getInstance().suggestParentWindow(project),
    "Local Files — ${session.displayName()}",
    ModalityType.MODELESS,
) {

    private var currentDir: File = initialDirectory()
    private val history = mutableListOf<File>()
    private var historyIndex = -1
    private var fileList = listOf<File>()

    private val pathField = JBTextField()
    private val statusLabel = JBLabel("Loading...")
    private val selectionLabel = JBLabel("")
    private val tableModel = LocalFileTableModel()
    private val table = JBTable(tableModel)

    init {
        isModal = false
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        setSize(JBUI.scale(740), JBUI.scale(500))
        minimumSize = Dimension(JBUI.scale(480), JBUI.scale(320))
        setLocationRelativeTo(parent)

        val root = JBPanel<JBPanel<*>>(BorderLayout())
        root.border = JBUI.Borders.empty()
        root.add(buildTopPanel(), BorderLayout.NORTH)
        root.add(JBScrollPane(table), BorderLayout.CENTER)
        root.add(buildStatusBar(), BorderLayout.SOUTH)

        contentPane = root

        setupTable()
        navigateTo(currentDir, addToHistory = true)
    }

    private fun initialDirectory(): File {
        val projectPath = project.basePath?.let { File(it) }
        if (projectPath != null && projectPath.exists() && projectPath.isDirectory) {
            return projectPath
        }
        val userHome = File(System.getProperty("user.home"))
        if (userHome.exists() && userHome.isDirectory) {
            return userHome
        }
        return File(".").canonicalFile
    }

    private fun buildTopPanel(): JPanel {
        val topPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(UIUtil.getBoundsColor(), 0, 0, 1, 0),
                JBUI.Borders.empty(4, 6),
            )
        }

        // Row 1: Address bar (Full width)
        val addressBar = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0, 4, 0)
        }
        pathField.addActionListener {
            val input = pathField.text.trim()
            val target = File(input)
            if (target.exists() && target.isDirectory) {
                navigateTo(target, addToHistory = true)
            } else if (target.exists() && target.isFile) {
                target.parentFile?.let { navigateTo(it, addToHistory = true) }
            }
        }
        val goBtn = HoverIconButton("Go to path (Enter)", AllIcons.Actions.Forward) {
            val input = pathField.text.trim()
            val target = File(input)
            if (target.exists() && target.isDirectory) {
                navigateTo(target, addToHistory = true)
            }
        }
        addressBar.add(pathField, BorderLayout.CENTER)
        addressBar.add(goBtn, BorderLayout.EAST)

        // Row 2: Action Icons
        val iconBar = JPanel(BorderLayout()).apply {
            isOpaque = false
        }
        val leftIcons = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            add(HoverIconButton("Back", AllIcons.Actions.Back) { navigateBack() })
            add(HoverIconButton("Forward", AllIcons.Actions.Forward) { navigateForward() })
            add(HoverIconButton("Up Folder", AllIcons.Nodes.UpFolder) { navigateUp() })
            add(HoverIconButton("Refresh", AllIcons.Actions.Refresh) { refreshCurrentDirectory() })

            add(createVerticalSeparator())

            val userHome = File(System.getProperty("user.home"))
            add(HoverIconButton("User Home (${userHome.name})", AllIcons.Nodes.HomeFolder) {
                navigateTo(userHome, addToHistory = true)
            })

            project.basePath?.let { p ->
                val pFile = File(p)
                if (pFile.exists()) {
                    add(HoverIconButton("Project Root (${pFile.name})", AllIcons.Nodes.Folder) {
                        navigateTo(pFile, addToHistory = true)
                    })
                }
            }

            val desktop = File(userHome, "Desktop")
            if (desktop.exists()) {
                add(HoverIconButton("Desktop", AllIcons.Nodes.Desktop) {
                    navigateTo(desktop, addToHistory = true)
                })
            }
        }

        val rightIcons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(HoverIconButton("Upload Selected to Session (${session.displayName()})", AllIcons.Actions.Upload) {
                uploadSelectedFiles()
            })
            add(HoverIconButton("Select All", AllIcons.Actions.Selectall) {
                if (table.rowCount > 0) {
                    table.setRowSelectionInterval(0, table.rowCount - 1)
                }
            })
        }

        iconBar.add(leftIcons, BorderLayout.WEST)
        iconBar.add(rightIcons, BorderLayout.EAST)

        topPanel.add(addressBar, BorderLayout.NORTH)
        topPanel.add(iconBar, BorderLayout.SOUTH)
        return topPanel
    }

    private fun createVerticalSeparator(): JSeparator = JSeparator(SwingConstants.VERTICAL).apply {
        preferredSize = Dimension(JBUI.scale(6), JBUI.scale(20))
        foreground = UIUtil.getBoundsColor()
    }

    private fun buildStatusBar(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1, 0, 0, 0),
                JBUI.Borders.empty(4, 8),
            )
            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
                isOpaque = false
                add(statusLabel)
                add(selectionLabel.apply { foreground = JBColor.namedColor("HaifaSSH.Accent", JBColor(0x2E7D32, 0x81C784)) })
            }
            val hintLabel = JBLabel("💡 Drag & drop files into remote session, or click Upload").apply {
                foreground = JBColor.GRAY
            }
            add(leftPanel, BorderLayout.WEST)
            add(hintLabel, BorderLayout.EAST)
        }
    }

    private fun setupTable() {
        // Support MULTIPLE file selection
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        table.autoCreateRowSorter = true
        table.rowHeight = JBUI.scale(22)
        table.setShowGrid(false)

        // Drag and drop export of javaFileListFlavor
        table.dragEnabled = true
        table.transferHandler = object : TransferHandler() {
            override fun getSourceActions(c: JComponent?): Int = DnDConstants.ACTION_COPY_OR_MOVE

            override fun createTransferable(c: JComponent?): Transferable? {
                val selected = getSelectedFiles()
                if (selected.isEmpty()) return null
                return object : Transferable {
                    override fun getTransferDataFlavors(): Array<DataFlavor> =
                        arrayOf(DataFlavor.javaFileListFlavor)

                    override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean =
                        flavor == DataFlavor.javaFileListFlavor

                    override fun getTransferData(flavor: DataFlavor?): Any {
                        if (flavor == DataFlavor.javaFileListFlavor) {
                            return selected
                        }
                        throw UnsupportedFlavorException(flavor)
                    }
                }
            }
        }

        // Custom renderer for file name and icon
        table.columnModel.getColumn(0).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: javax.swing.JTable?,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): java.awt.Component {
                super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column)
                val modelRow = table.convertRowIndexToModel(row)
                val file = tableModel.getFileAt(modelRow)
                if (file != null) {
                    text = file.name.ifEmpty { file.path }
                    icon = when {
                        file.isDirectory -> AllIcons.Nodes.Folder
                        else -> AllIcons.FileTypes.Any_type
                    }
                }
                return this
            }
        }

        // Align size to right
        table.columnModel.getColumn(1).cellRenderer = DefaultTableCellRenderer().apply {
            horizontalAlignment = javax.swing.SwingConstants.RIGHT
        }

        // Double click handler
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) {
                    val row = table.selectedRow
                    if (row >= 0) {
                        val file = tableModel.getFileAt(table.convertRowIndexToModel(row)) ?: return
                        if (file.isDirectory) {
                            navigateTo(file, addToHistory = true)
                        } else {
                            uploadSelectedFiles()
                        }
                    }
                } else if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) {
                    showContextMenu(e)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showContextMenu(e)
                }
            }
        })

        // Selection listener to update status bar
        table.selectionModel.addListSelectionListener {
            updateSelectionStatus()
        }

        // Keyboard navigation
        table.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> {
                        val selected = getSelectedFiles()
                        if (selected.size == 1 && selected[0].isDirectory) {
                            navigateTo(selected[0], addToHistory = true)
                            e.consume()
                        } else if (selected.isNotEmpty()) {
                            uploadSelectedFiles()
                            e.consume()
                        }
                    }
                    KeyEvent.VK_BACK_SPACE -> {
                        navigateUp()
                        e.consume()
                    }
                    KeyEvent.VK_F5 -> {
                        refreshCurrentDirectory()
                        e.consume()
                    }
                }
            }
        })
    }

    private fun getSelectedFiles(): List<File> {
        val rows = table.selectedRows
        if (rows.isEmpty()) return emptyList()
        return rows.map { row ->
            val modelRow = table.convertRowIndexToModel(row)
            tableModel.getFileAt(modelRow)
        }.filterNotNull()
    }

    private fun updateSelectionStatus() {
        val selected = getSelectedFiles()
        if (selected.isEmpty()) {
            selectionLabel.text = ""
        } else {
            val totalBytes = selected.sumOf { if (it.isFile) it.length() else 0L }
            val dirCount = selected.count { it.isDirectory }
            val fileCount = selected.size - dirCount
            val sizeStr = if (totalBytes > 0) " (${formatSize(totalBytes)})" else ""
            val detail = if (dirCount > 0 && fileCount > 0) {
                "${selected.size} selected ($dirCount folders, $fileCount files$sizeStr)"
            } else {
                "${selected.size} selected$sizeStr"
            }
            selectionLabel.text = detail
        }
    }

    private fun showContextMenu(e: MouseEvent) {
        val row = table.rowAtPoint(e.point)
        if (row >= 0 && !table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row)
        }
        val selected = getSelectedFiles()

        val menu = JPopupMenu()
        menu.add(JMenuItem("Upload to Remote Session (${session.displayName()})", AllIcons.Actions.Upload).apply {
            isEnabled = selected.isNotEmpty()
            addActionListener { uploadSelectedFiles() }
        })
        menu.addSeparator()

        if (selected.size == 1) {
            val file = selected[0]
            if (file.isDirectory) {
                menu.add(JMenuItem("Open Folder", AllIcons.Nodes.Folder).apply {
                    addActionListener { navigateTo(file, addToHistory = true) }
                })
            } else {
                menu.add(JMenuItem("Open Locally", AllIcons.Actions.Edit).apply {
                    addActionListener {
                        runCatching { Desktop.getDesktop().open(file) }
                    }
                })
            }
            menu.add(JMenuItem("Copy Local Path", AllIcons.Actions.Copy).apply {
                addActionListener {
                    val sel = StringSelection(file.absolutePath)
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                }
            })
        } else if (selected.size > 1) {
            menu.add(JMenuItem("Copy Paths (${selected.size} files)", AllIcons.Actions.Copy).apply {
                addActionListener {
                    val paths = selected.joinToString("\n") { it.absolutePath }
                    val sel = StringSelection(paths)
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                }
            })
        }

        menu.add(JMenuItem("Select All", AllIcons.Actions.Selectall).apply {
            addActionListener {
                if (table.rowCount > 0) {
                    table.setRowSelectionInterval(0, table.rowCount - 1)
                }
            }
        })
        menu.addSeparator()
        menu.add(JMenuItem("Refresh", AllIcons.Actions.Refresh).apply {
            addActionListener { refreshCurrentDirectory() }
        })

        menu.show(e.component, e.x, e.y)
    }

    fun navigateTo(dir: File, addToHistory: Boolean = false) {
        val canonical = runCatching { dir.canonicalFile }.getOrDefault(dir)
        if (!canonical.exists() || !canonical.isDirectory) return

        currentDir = canonical
        pathField.text = canonical.absolutePath

        val files = runCatching {
            canonical.listFiles()?.toList() ?: emptyList()
        }.getOrDefault(emptyList())

        fileList = files.sortedWith(
            compareBy<File> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
        tableModel.setFiles(fileList)

        if (addToHistory) {
            if (historyIndex < history.size - 1) {
                history.subList(historyIndex + 1, history.size).clear()
            }
            history.add(canonical)
            historyIndex = history.size - 1
        }

        val dirCount = fileList.count { it.isDirectory }
        val fileCount = fileList.size - dirCount
        statusLabel.text = "${fileList.size} items ($dirCount folders, $fileCount files)"
        updateSelectionStatus()
    }

    private fun navigateUp() {
        val parent = currentDir.parentFile ?: return
        navigateTo(parent, addToHistory = true)
    }

    private fun navigateBack() {
        if (historyIndex > 0) {
            historyIndex--
            navigateTo(history[historyIndex], addToHistory = false)
        }
    }

    private fun navigateForward() {
        if (historyIndex < history.size - 1) {
            historyIndex++
            navigateTo(history[historyIndex], addToHistory = false)
        }
    }

    private fun refreshCurrentDirectory() {
        navigateTo(currentDir, addToHistory = false)
    }

    private fun uploadSelectedFiles() {
        val selected = getSelectedFiles()
        if (selected.isNotEmpty()) {
            onUploadFiles(selected)
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }

    private inner class LocalFileTableModel : AbstractTableModel() {
        private val columns = arrayOf("Name", "Size", "Type", "Modified")
        private var files: List<File> = emptyList()
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

        fun setFiles(newFiles: List<File>) {
            files = newFiles
            fireTableDataChanged()
        }

        fun getFileAt(row: Int): File? = files.getOrNull(row)

        override fun getRowCount(): Int = files.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val file = files[rowIndex]
            return when (columnIndex) {
                0 -> file.name.ifEmpty { file.path }
                1 -> if (file.isDirectory) "-" else formatSize(file.length())
                2 -> if (file.isDirectory) "Folder" else (file.extension.uppercase().ifEmpty { "File" })
                3 -> {
                    val lm = file.lastModified()
                    if (lm > 0) dateFormat.format(Date(lm)) else "-"
                }
                else -> ""
            }
        }
    }
}
