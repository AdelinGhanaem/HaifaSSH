package com.haifasoft.haifassh.fs

import com.haifasoft.haifassh.fs.ui.FileTableTypeAhead
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.event.KeyEvent
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

class FileTableTypeAheadTest {

    @Test
    fun testTypeAheadFindsDownloads() {
        val fileNames = listOf("Applications", "Desktop", "Documents", "Downloads", "Music", "Pictures")
        val model = DefaultTableModel(arrayOf("Name"), 0)
        fileNames.forEach { model.addRow(arrayOf(it)) }

        val table = JTable(model)
        val listener = FileTableTypeAhead(table) { row -> model.getValueAt(row, 0) as? String }

        // Simulate typing 'D', 'o', 'w', 'n'
        for (char in "Down") {
            val event = KeyEvent(table, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, char)
            listener.keyTyped(event)
        }

        assertEquals("Downloads row index should be selected", 3, table.selectedRow)
        assertEquals("Downloads", model.getValueAt(table.selectedRow, 0))
    }

    @Test
    fun testCaseInsensitiveSearch() {
        val fileNames = listOf("Archive.tar", "Build", "config.json", "docker-compose.yml")
        val model = DefaultTableModel(arrayOf("Name"), 0)
        fileNames.forEach { model.addRow(arrayOf(it)) }

        val table = JTable(model)
        val listener = FileTableTypeAhead(table) { row -> model.getValueAt(row, 0) as? String }

        // Simulate typing "DOCK" in uppercase
        for (char in "DOCK") {
            val event = KeyEvent(table, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, char)
            listener.keyTyped(event)
        }

        assertEquals(3, table.selectedRow)
        assertEquals("docker-compose.yml", model.getValueAt(table.selectedRow, 0))
    }

    @Test
    fun testSingleCharacterCycle() {
        val fileNames = listOf("alpha", "beta", "delta1", "delta2", "delta3", "gamma")
        val model = DefaultTableModel(arrayOf("Name"), 0)
        fileNames.forEach { model.addRow(arrayOf(it)) }

        val table = JTable(model)
        val listener = FileTableTypeAhead(table) { row -> model.getValueAt(row, 0) as? String }

        // Type 'd' -> should select delta1 (index 2)
        listener.keyTyped(KeyEvent(table, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, 'd'))
        assertEquals(2, table.selectedRow)

        // Type 'd' again immediately -> should cycle to delta2 (index 3)
        listener.keyTyped(KeyEvent(table, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, 'd'))
        assertEquals(3, table.selectedRow)

        // Type 'd' again immediately -> should cycle to delta3 (index 4)
        listener.keyTyped(KeyEvent(table, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, 'd'))
        assertEquals(4, table.selectedRow)
    }
}
