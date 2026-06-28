package com.haifasoft.haifassh.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColorPanel
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.haifasoft.haifassh.model.SshAuthType
import com.haifasoft.haifassh.model.SshSession
import com.haifasoft.haifassh.settings.SshCredentialStore
import com.haifasoft.haifassh.settings.SshSessionService
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Create/edit dialog for an [SshSession], including auth, credential storage,
 * and an editable list of jump hosts. Secrets are persisted to PasswordSafe via
 * [persist]; never to the session XML.
 */
class SshSessionDialog(
    private val project: Project,
    private val session: SshSession,
    private val isNew: Boolean,
) : DialogWrapper(project) {

    private val nameField = JBTextField(session.name)
    private val hostField = JBTextField(session.host)
    private val portField = JBIntSpinner(session.port.coerceIn(1, 65535), 1, 65535)
    private val usernameField = JBTextField(session.username)
    private val authCombo = ComboBox(arrayOf(SshAuthType.PASSWORD, SshAuthType.KEY)).apply {
        selectedItem = session.authType
        renderer = SimpleListCellRenderer.create("") { JumpHostDialog.authLabel(it) }
    }
    private val keyPathField = TextFieldWithBrowseButton().apply { text = session.privateKeyPath }
    private val passwordField = JBPasswordField()
    private val passphraseField = JBPasswordField()
    private val savePasswordCheck = JBCheckBox("Store credentials in IDE password safe", session.savePassword)
    private val allowLegacyCheck = JBCheckBox(
        "Allow legacy SHA-1/DSA algorithms (only for old servers)",
        session.allowLegacyAlgorithms,
    ).apply {
        toolTipText = "Enables the weak ssh-rsa/ssh-dss algorithms as a fallback. " +
            "Leave off unless this host offers nothing modern."
    }

    private val iconCombo = ComboBox(SessionIcons.choices.toTypedArray()).apply {
        selectedItem = SessionIcons.choice(session.iconKey)
        renderer = SimpleListCellRenderer.create<SessionIcons.Choice> { label, value, _ ->
            label.text = value?.label ?: ""
            label.icon = value?.icon
        }
    }
    private val colorPanel = ColorPanel().apply {
        selectedColor = AppearanceColors.parse(session.color)
    }
    private val resetColorButton = JButton("Default").apply {
        addActionListener { colorPanel.selectedColor = null }
    }

    private val jumpModel = CollectionListModel<JumpHostEntry>()
    private val jumpList = JBList(jumpModel).apply {
        cellRenderer = SimpleListCellRenderer.create("<no jump hosts>") { it.label() }
        visibleRowCount = 4
    }

    init {
        title = if (isNew) "New SSH Session" else "Edit SSH Session"

        keyPathField.addBrowseFolderListener(
            "Select Private Key",
            "Choose the private key file for this session",
            project,
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor(),
        )

        if (!isNew) {
            passwordField.text = SshCredentialStore.getPassword(session.id) ?: ""
            passphraseField.text = SshCredentialStore.getPassphrase(session.id) ?: ""
            session.jumpHosts.forEachIndexed { index, jump ->
                jumpModel.add(
                    JumpHostEntry(
                        host = jump.copyOf(),
                        password = SshCredentialStore.getJumpPassword(session.id, index),
                        passphrase = SshCredentialStore.getJumpPassphrase(session.id, index),
                    ),
                )
            }
        }

        authCombo.addActionListener { updateAuthFields() }
        init()
        updateAuthFields()
    }

    override fun createCenterPanel(): JComponent {
        val jumpPanel = ToolbarDecorator.createDecorator(jumpList)
            .setAddAction { addJump() }
            .setEditAction { editJump() }
            .setRemoveAction { removeJump() }
            .setPreferredSize(JBUI.size(360, 110))
            .createPanel()

        val colorRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(colorPanel)
            add(resetColorButton)
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameField)
            .addLabeledComponent("Host:", hostField)
            .addLabeledComponent("Port:", portField)
            .addLabeledComponent("Username:", usernameField)
            .addLabeledComponent("Authentication:", authCombo)
            .addLabeledComponent("Password:", passwordField)
            .addLabeledComponent("Private key:", keyPathField)
            .addLabeledComponent("Passphrase:", passphraseField)
            .addComponent(savePasswordCheck)
            .addComponent(allowLegacyCheck)
            .addLabeledComponent("Icon:", iconCombo)
            .addLabeledComponent("Color:", colorRow)
            .addLabeledComponentFillVertically("Jump hosts:", jumpPanel)
            .panel
    }

    private fun updateAuthFields() {
        val key = authCombo.selectedItem == SshAuthType.KEY
        passwordField.isEnabled = !key
        keyPathField.isEnabled = key
        passphraseField.isEnabled = key
    }

    private fun addJump() {
        val entry = JumpHostEntry()
        if (JumpHostDialog(project, entry, isNew = true).showAndGet()) {
            jumpModel.add(entry)
        }
    }

    private fun editJump() {
        val index = jumpList.selectedIndex
        if (index < 0) return
        val entry = jumpModel.getElementAt(index)
        if (JumpHostDialog(project, entry, isNew = false).showAndGet()) {
            jumpList.repaint()
        }
    }

    private fun removeJump() {
        val index = jumpList.selectedIndex
        if (index >= 0) jumpModel.remove(index)
    }

    override fun doValidate(): ValidationInfo? {
        if (hostField.text.isBlank()) return ValidationInfo("Host is required", hostField)
        if (usernameField.text.isBlank()) return ValidationInfo("Username is required", usernameField)
        if (authCombo.selectedItem == SshAuthType.KEY && keyPathField.text.isBlank()) {
            return ValidationInfo("Private key path is required for key authentication", keyPathField)
        }
        return null
    }

    /** Writes the edited values + secrets through the service. Call after [showAndGet] returns true. */
    fun persist(service: SshSessionService): SshSession {
        session.name = nameField.text.trim()
        session.host = hostField.text.trim()
        session.port = portField.number
        session.username = usernameField.text.trim()
        session.authType = authCombo.selectedItem as SshAuthType
        session.privateKeyPath = keyPathField.text.trim()
        session.savePassword = savePasswordCheck.isSelected
        session.allowLegacyAlgorithms = allowLegacyCheck.isSelected
        session.iconKey = (iconCombo.selectedItem as? SessionIcons.Choice)?.key.orEmpty()
        session.color = colorPanel.selectedColor?.let { AppearanceColors.toHex(it) } ?: ""
        session.jumpHosts = jumpModel.items.map { it.host }.toMutableList()

        val saved = service.upsertSession(session)
        persistSecrets(saved)
        return saved
    }

    private fun persistSecrets(saved: SshSession) {
        val store = SshCredentialStore
        if (saved.savePassword) {
            store.setPassword(saved.id, String(passwordField.password).takeIf { saved.authType == SshAuthType.PASSWORD })
            store.setPassphrase(saved.id, String(passphraseField.password).takeIf { saved.authType == SshAuthType.KEY })
        } else {
            store.setPassword(saved.id, null)
            store.setPassphrase(saved.id, null)
        }

        val entries = jumpModel.items
        entries.forEachIndexed { index, entry ->
            if (saved.savePassword) {
                store.setJumpPassword(saved.id, index, entry.password)
                store.setJumpPassphrase(saved.id, index, entry.passphrase)
            } else {
                store.setJumpPassword(saved.id, index, null)
                store.setJumpPassphrase(saved.id, index, null)
            }
        }
        // Drop secrets left over from jump hosts that were removed.
        for (index in entries.size until entries.size + 16) {
            store.setJumpPassword(saved.id, index, null)
            store.setJumpPassphrase(saved.id, index, null)
        }
    }
}
