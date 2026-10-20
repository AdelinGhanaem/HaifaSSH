package com.haifasoft.haifassh.fs.ui

import com.intellij.ui.TableSpeedSearch
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JTable

/**
 * Provides file explorer-like type-ahead search for file tables.
 * When a user types characters (e.g. "Down"), it searches file names,
 * automatically jumps row selection to the matching row (e.g. "Downloads"),
 * and scrolls it into view. Also installs IntelliJ's TableSpeedSearch.
 */
class FileTableTypeAhead(
    private val table: JTable,
    private val getFileName: (modelRow: Int) -> String?,
) : KeyAdapter() {

    private val buffer = StringBuilder()
    private var lastKeyTime = 0L

    companion object {
        fun installOn(table: JTable, getFileName: (modelRow: Int) -> String?): FileTableTypeAhead {
            val listener = FileTableTypeAhead(table, getFileName)
            table.addKeyListener(listener)
            runCatching {
                TableSpeedSearch.installOn(table)
            }
            return listener
        }
    }

    override fun keyTyped(e: KeyEvent) {
        val ch = e.keyChar
        if (ch.isISOControl() || e.isMetaDown || e.isControlDown || e.isAltDown) return

        // Space only counts if already typing a multi-word name
        if (ch == ' ' && buffer.isEmpty()) return

        val now = System.currentTimeMillis()
        if (now - lastKeyTime > 1200L) {
            buffer.setLength(0)
        }
        lastKeyTime = now
        buffer.append(ch)

        val query = buffer.toString().lowercase()
        val rowCount = table.rowCount
        if (rowCount == 0) return

        // Check if single repeating character (e.g. typing 'd', 'd', 'd' to cycle through D-items)
        val isSingleCharCycle = query.length > 1 && query.all { it == query[0] }
        val searchPrefix = if (isSingleCharCycle) query.substring(0, 1) else query

        val currentRow = table.selectedRow.coerceAtLeast(0)
        val startIndex = if (isSingleCharCycle) (currentRow + 1) % rowCount else 0

        var targetRow = -1

        // 1. Try prefix search (starts with)
        for (i in 0 until rowCount) {
            val r = (startIndex + i) % rowCount
            val modelRow = table.convertRowIndexToModel(r)
            val name = getFileName(modelRow)?.lowercase() ?: continue
            if (name.startsWith(searchPrefix)) {
                targetRow = r
                break
            }
        }

        // 2. Fallback to contains search if not single char cycle
        if (targetRow == -1 && !isSingleCharCycle) {
            for (i in 0 until rowCount) {
                val r = (startIndex + i) % rowCount
                val modelRow = table.convertRowIndexToModel(r)
                val name = getFileName(modelRow)?.lowercase() ?: continue
                if (name.contains(searchPrefix)) {
                    targetRow = r
                    break
                }
            }
        }

        if (targetRow != -1) {
            table.setRowSelectionInterval(targetRow, targetRow)
            table.scrollRectToVisible(table.getCellRect(targetRow, 0, true))
        }
    }
}
