package com.haifasoft.haifassh.ui

import com.intellij.openapi.util.IconLoader

/**
 * Plugin icons. These are custom (not platform) icons, so the New UI renders them
 * in their authored colors instead of desaturating them to gray.
 */
object HaifaIcons {
    @JvmField
    val Session = IconLoader.getIcon("/icons/sshSession.svg", HaifaIcons::class.java)
}
