package com.haifasoft.haifassh.terminal

import com.haifasoft.haifassh.settings.SshCommandHistoryService

/**
 * Reconstructs the command lines a user types into a shell by observing the raw
 * keystrokes flowing out through [SshTtyConnector], and records each completed
 * line (on Enter) into [SshCommandHistoryService] under [hostKey].
 *
 * This is a deliberate heuristic on the outgoing byte stream — it has no
 * knowledge of the remote shell's line editor. It handles plain typing,
 * backspace and a few common line-kill controls, but by design it does NOT
 * faithfully capture:
 *  - history recall via the arrow keys (the recalled text never crosses this
 *    stream, so an up-arrow + Enter records nothing),
 *  - tab-completion (the completion is chosen remotely),
 *  - multi-line constructs such as heredocs or continued lines.
 * Any escape sequence (cursor keys, query responses JediTerm writes back, etc.)
 * resets the in-progress line rather than corrupting it.
 *
 * All state is guarded by the instance monitor; JediTerm calls write() off the
 * EDT on its own thread.
 */
class TerminalCommandRecorder(private val hostKey: String) {

    private val history = SshCommandHistoryService.getInstance()
    private val currentLine = StringBuilder()

    /** Feeds one chunk of outgoing keystroke text (already decoded to a String). */
    @Synchronized
    fun onOutgoing(text: String) {
        for (c in text) {
            when (c) {
                '\r', '\n' -> commit()
                '\b', DEL -> if (currentLine.isNotEmpty()) currentLine.deleteCharAt(currentLine.length - 1)
                CTRL_C, CTRL_U, CTRL_W, ESC -> currentLine.setLength(0)
                else -> if (!c.isISOControl()) currentLine.append(c)
            }
        }
    }

    private fun commit() {
        val line = currentLine.toString()
        currentLine.setLength(0)
        if (line.isNotBlank()) history.record(hostKey, line)
    }

    private companion object {
        const val ESC = '\u001B'
        const val DEL = '\u007F'
        const val CTRL_C = '\u0003'
        const val CTRL_U = '\u0015'
        const val CTRL_W = '\u0017'
    }
}
