package com.haifasoft.haifassh.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/**
 * Asks how many sessions to export and whether to include credentials. Metadata
 * is always exported; secrets are opt-in because the file then contains plaintext
 * passwords and passphrases.
 */
class ExportOptionsDialog(project: Project, private val sessionCount: Int) : DialogWrapper(project) {

    private val includeSecretsCheck = JBCheckBox("Include saved passwords & passphrases", false)

    init {
        title = "Export SSH Sessions"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val warning = JBLabel(
            "<html><body style='width:320px'>If checked, the exported file will contain your " +
                "<b>plaintext</b> passwords and passphrases. Anyone with the file can read them. " +
                "Leave unchecked to export configuration only.</body></html>",
        ).apply { border = JBUI.Borders.emptyTop(6) }

        return FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Exporting $sessionCount session(s)."))
            .addComponent(includeSecretsCheck)
            .addComponent(warning)
            .panel
    }

    val includeSecrets: Boolean
        get() = includeSecretsCheck.isSelected
}
