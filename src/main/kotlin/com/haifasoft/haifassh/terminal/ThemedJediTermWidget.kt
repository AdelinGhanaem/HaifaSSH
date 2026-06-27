package com.haifasoft.haifassh.terminal

import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.SettingsProvider
import com.haifasoft.haifassh.terminal.theme.TerminalSchemes
import com.haifasoft.haifassh.terminal.theme.toTerminalColor

/**
 * A JediTerm widget whose default cell style (used for text the shell prints
 * without explicit color codes — e.g. the MOTD on first connect) is initialized
 * from the active [com.haifasoft.haifassh.terminal.theme.TerminalColorScheme].
 *
 * Without this, JediTerm's stock [StyleState] defaults to white-on-black, so the
 * first lines rendered before any SGR escape appeared white regardless of theme.
 */
class ThemedJediTermWidget(settingsProvider: SettingsProvider) : JediTermWidget(settingsProvider) {

    // Assigned from within the super constructor (createDefaultStyle); lateinit so a
    // property initializer doesn't overwrite it afterwards.
    private lateinit var styleState: StyleState

    override fun createDefaultStyle(): StyleState {
        val state = StyleState()
        state.setDefaultStyle(currentDefaultStyle())
        styleState = state
        return state
    }

    /** Re-reads the active scheme into the default style and repaints (theme/scheme switch). */
    fun reapplyScheme() {
        if (::styleState.isInitialized) {
            styleState.setDefaultStyle(currentDefaultStyle())
        }
        runCatching { terminalPanel.repaint() }
    }

    private fun currentDefaultStyle(): TextStyle {
        val scheme = TerminalSchemes.active()
        return TextStyle(scheme.foreground.toTerminalColor(), scheme.background.toTerminalColor())
    }
}
