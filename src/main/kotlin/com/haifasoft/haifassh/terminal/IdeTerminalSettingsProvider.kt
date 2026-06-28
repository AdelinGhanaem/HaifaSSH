package com.haifasoft.haifassh.terminal

import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.ColorPalette
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.haifasoft.haifassh.settings.HaifaSshSettings
import com.haifasoft.haifassh.terminal.theme.SchemeColorPalette
import com.haifasoft.haifassh.terminal.theme.TerminalSchemes
import com.haifasoft.haifassh.terminal.theme.toTerminalColor

/**
 * Feeds the active [TerminalColorScheme][com.haifasoft.haifassh.terminal.theme.TerminalColorScheme]
 * (chosen in settings — a template, a custom scheme, or "Follow IDE Theme") into
 * JediTerm: default colors, the full 16-color ANSI palette, and selection colors.
 *
 * Colors are read live on every call, so re-painting after a theme or scheme
 * change immediately reflects the new colors.
 */
class IdeTerminalSettingsProvider : DefaultSettingsProvider() {

    private fun scheme() = TerminalSchemes.active()

    override fun getDefaultForeground(): TerminalColor = scheme().foreground.toTerminalColor()

    override fun getDefaultBackground(): TerminalColor = scheme().background.toTerminalColor()

    override fun getTerminalColorPalette(): ColorPalette = SchemeColorPalette(scheme())

    override fun getSelectionColor(): TextStyle {
        val s = scheme()
        return TextStyle(s.selectionForeground.toTerminalColor(), s.selectionBackground.toTerminalColor())
    }

    // Use our explicit selection colors instead of inverting fg/bg (which rendered
    // selections as a near-black block on dark themes).
    override fun useInverseSelectionColor(): Boolean = false

    // User-configurable scrollback (history) size, read when a terminal is created
    // so a large value can hold, e.g., the full output of `tail -f -n 10000`.
    override fun getBufferMaxLinesCount(): Int = HaifaSshSettings.getInstance().terminalBufferMaxLines
}
