# HaifaSSH Code Guide

A file-by-file walkthrough so you can read the source and understand every piece.
The code itself is already KDoc-commented; this guide adds the *connective
tissue* — responsibilities, key methods, gotchas, and how files call each other.
Read it alongside [02-implementation.md](02-implementation.md).

Source root: `src/main/kotlin/com/haifasoft/haifassh/`
Resources: `src/main/resources/` (`META-INF/plugin.xml`, `icons/…`).

Suggested reading order: **model → settings → ssh → terminal → ui**.

---

## `META-INF/plugin.xml` — the descriptor

Declares the plugin id/name/version, the description & change-notes (HTML shown in
the Plugins UI), `<depends>com.intellij.modules.platform</depends>`, and the
**extensions** that wire code into the platform:

- two `toolWindow`s — `SSH Sessions` (left) and `SSH Terminals` (bottom);
- a `fileEditorProvider` (terminals-as-editor-tabs);
- a `notificationGroup` (`HaifaSSH`);
- an `applicationConfigurable` (the terminal color settings page).

When you add a new tool window, editor, settings page, or listener, this is where
it gets registered. Build numbers/compatibility (`sinceBuild`, no `untilBuild`)
are set in `build.gradle.kts`, not here.

---

## `model/` — the serializable domain

Plain mutable beans with default values so the platform XML serializer can
round-trip them (see doc 01 §3). No behavior beyond small helpers.

- **`SshAuthType.kt`** — enum `PASSWORD | KEY`.
- **`JumpHost.kt`** — one bastion hop (host/port/user/auth/keyPath). `copyOf()`
  for defensive copies; `label()` → `user@host:port` for display. Secrets are
  *not* here (they live in PasswordSafe, keyed by session id + index).
- **`SshFolder.kt`** — tree folder: `id`, `name`, `parentId` (empty = top level),
  and `color` (`#RRGGBB`, blank = default — added in 0.5.0).
- **`SshSession.kt`** — a session: identity (`id`), `name`, `folderId`,
  connection (`host`/`port`/`username`), auth (`authType`/`privateKeyPath`),
  `savePassword`, appearance (`iconKey`/`color`, 0.5.0), and a `jumpHosts` list
  (`@XCollection`). `displayName()` falls back to `user@host` then "Unnamed
  session". `copyOf()` produces an editable copy for the dialog (so Cancel doesn't
  mutate the stored object). **Reading note:** when you add a field here, also add
  it to `copyOf()`, to `SshSessionExport`, and to both directions of
  `SshSessionTransfer` — that quartet must stay in sync.

---

## `settings/` — persistence, preferences, secrets, import/export

### `SshSessionService.kt` — the session tree store
`@Service(APP)` + `@State(... "haifassh-sessions.xml")`. The single source of
truth for folders + sessions.

- **Reads:** `folders()/sessions()`, `foldersIn(parentId)`/`sessionsIn(folderId)`
  (sorted, used to build the tree), `findFolder/findSession`.
- **Writes:** `addFolder`, `renameFolder`, `renameSession`, `setFolderColor`,
  `setSessionAppearance`, `upsertSession` (assigns a UUID if blank; insert-or-
  replace by id), `importBundle` (bulk add for import), `deleteSession`/
  `deleteFolder` (the latter removes the whole subtree via `collectSubtree`, a
  breadth-first walk, and clears each removed session's secrets).
- Every mutation calls **`fireChanged()`** → publishes `TOPIC` on the message bus.
  Panels subscribe and `reload()`. This is how multiple windows stay in sync.

**Reading note:** the service never touches secrets except to *delete* them on
removal — secret writes happen in the dialog/transfer code via `SshCredentialStore`.

### `SshSessionState.kt`
The serialized container: two `@XCollection` lists (`folders`, `sessions`). Held
by the service; this is literally what becomes the XML.

### `SshCredentialStore.kt` — secrets in PasswordSafe
An `object` wrapping `PasswordSafe.instance`. Keys are namespaced
`HaifaSSH/<sessionId>/password|passphrase` and
`HaifaSSH/<sessionId>/jump/<index>/password|passphrase` via
`generateServiceName(...)`. `write` stores `null` for empty strings (so clearing a
field removes the entry). `clearSession` best-effort wipes a session's secrets up
to `maxJumpHosts = 16`.

**Reading note:** the "16" bound appears here and in the dialog's leftover-cleanup
loop — a known edge case if a session ever had >16 jump hosts.

### `HaifaSshSettings.kt` — plugin preferences
`@Service(APP)` + `@State(... "haifassh-settings.xml")`. Holds `openInEditor`
(terminal placement), `terminalSchemeName` (active scheme), and `custom`
(`StoredScheme` — the editable scheme as hex strings, with sane defaults and a
`toScheme()`/`copyFrom()` bridge to the runtime `TerminalColorScheme`). Defines
`SCHEME_TOPIC` + `fireSchemeChanged()` so open terminals repaint when colors
change.

### `SshSessionExport.kt` — the portable bundle
Transport-only beans: `SshExportBundle` (`formatVersion`, `includesSecrets`,
folders, sessions), `SshSessionExport` (a session **plus optional inline plaintext
secrets** and the 0.5.0 `iconKey`/`color`), and `JumpHostExport`. Deliberately
separate from the live model because a bundle may carry secrets the live model
never does.

### `SshSessionTransfer.kt` — import/export logic
- **`export(path, includeSecrets)`** builds a bundle from the service, copying
  folder/session fields and (if opted in) pulling secrets from `SshCredentialStore`
  inline; serializes with `XmlSerializer` + `JDOMUtil.write`.
- **`import(path)`** deserializes, then **assigns fresh UUIDs** to every folder
  and session and **remaps parent/folder references** through `folderIdMap`. This
  makes import a **merge** — it can never clobber an existing session. If the
  bundle included secrets, they're written to PasswordSafe for the new ids.

**Reading note:** this is the other place the `model ↔ export` field quartet must
stay in sync; colors/icons are copied in both `toExport` and the import mapper.

---

## `ssh/` — the transport layer (JSch)

### `HopConfig.kt`
An immutable `data class`: one fully-resolved hop with secrets already fetched.
The boundary between "stored config + PasswordSafe" and "ready to connect."

### `SshConnector.kt` — opening shells & chaining jump hosts
An `object`. `openShell(chain, termType, columns, rows)`:

1. Iterates the hop chain. Hop 0 connects directly; each later hop is reached via
   `previous.setPortForwardingL("127.0.0.1", 0, hop.host, hop.port)` (loopback
   ephemeral port) — this is the ProxyJump mechanism.
2. Per hop: `applyAuth` (password → `setPassword` + `PreferredAuthentications=
   password,keyboard-interactive`; key → `jsch.addIdentity(path[, passphrase])` +
   `PreferredAuthentications=publickey`), then `StrictHostKeyChecking=no`,
   `enableLegacyAlgorithms`, keep-alive, `connect(timeout)`.
3. Opens a `shell` `ChannelShell` with a PTY, returns an `SshShellHandle`.
4. On any failure, disconnects every opened session in reverse order.

**`enableLegacyAlgorithms` / `mergeAlgorithms`** append `ssh-rsa`/`ssh-dss` to
`server_host_key` and `PubkeyAcceptedAlgorithms` *after* the modern entries (so
they're fallback-only). **Security-critical reading:** lines 40
(`StrictHostKeyChecking=no`) and 72–86 (legacy algos) are the two findings in the
security report — see [04-security-report.md](04-security-report.md).

### `SshShellHandle.kt`
Owns the live `ChannelShell` and the whole `Session` chain. Exposes
`inputStream`/`outputStream` (used by the TTY bridge), `isConnected`, `resize`,
`exitStatus`, and `close()` (disconnects channel then sessions in reverse). This
is the single object the terminal layer holds onto.

---

## `terminal/` — emulator integration

### `SshTerminalManager.kt` — orchestration (`@Service(PROJECT)`)
The hub that turns a `SshSession` into a visible terminal.

- **`connect(session)`** — validates host; `buildChain` resolves all hop secrets
  on the EDT (prompting via `Messages.showPasswordDialog` if a password is missing
  — note this runs **before** the background task, intentionally, so dialogs are
  on the EDT); then a `Task.Backgroundable` runs `SshConnector.openShell` off the
  EDT; `onSuccess` opens the UI, `onThrowable` closes the handle and notifies.
- **`open` → `openInEditorArea` | `openTerminalTab`** per `openInEditor`. The
  editor path wraps the handle in `SshTerminalVirtualFile` and calls
  `FileEditorManager.openFile`. The tool-window path builds the widget directly,
  ties a per-tab `Disposer.newDisposable(...)` lifetime, and on content disposal
  closes the widget, the lifetime, and the handle.
- **`resolveHop`** is the password-prompt-or-fail helper; returns `null` if the
  user cancels (which aborts the whole connect).

### `SshTtyConnector.kt` — JediTerm ⇄ SSH bridge
Implements JediTerm's `TtyConnector` over the handle's streams: `read` from the
PTY (UTF-8 `InputStreamReader`), `write` to it, `resize`, `waitFor` (polls
`isConnected` every 100 ms then returns `exitStatus`), `close`.

### `SshTerminalUi.kt`
`createWidget(session, handle, parentDisposable)`: builds a `ThemedJediTermWidget`
with an `IdeTerminalSettingsProvider`, attaches the `SshTtyConnector`, `start()`s
it, and subscribes (scoped to `parentDisposable`) to **`LafManagerListener.TOPIC`**
(IDE theme switch) and **`HaifaSshSettings.SCHEME_TOPIC`** (scheme change) → both
call `widget.reapplyScheme()`. Shared by both presentations so they look identical.

### `ThemedJediTermWidget.kt`
Subclass that overrides `createDefaultStyle()` to seed JediTerm's default cell
style from the active scheme (fixes the "first lines render white" bug — pre-SGR
text now follows the theme). `reapplyScheme()` re-reads the scheme into the style
and repaints. `styleState` is `lateinit` because it's assigned from inside the
super constructor.

### `IdeTerminalSettingsProvider.kt`
Extends JediTerm's `DefaultSettingsProvider`; returns default fg/bg, the
`SchemeColorPalette`, and explicit selection colors from the **active** scheme
(read live every call). `useInverseSelectionColor() = false` so selections use our
colors rather than inverting (which looked near-black on dark themes).

### `SshTerminalsToolWindowFactory.kt`
The bottom tool window. Intentionally empty — tabs are created on demand by
`SshTerminalManager` when a session connects.

### `terminal/editor/` — terminal as an editor tab
- **`SshTerminalVirtualFile.kt`** — an in-memory `LightVirtualFile` carrying the
  `session` + already-opened `handle`; non-writable; identity equality so each
  connect opens a separate tab.
- **`SshTerminalFileEditorProvider.kt`** — accepts *only* `SshTerminalVirtualFile`
  (never interferes with real files), builds the editor, `HIDE_DEFAULT_EDITOR`.
- **`SshTerminalFileEditor.kt`** — a `FileEditor` (which *is* a `Disposable`)
  hosting the JediTerm widget; `dispose()` closes the widget and the SSH handle
  when the tab closes.

### `terminal/theme/` — color schemes
- **`TerminalColorScheme.kt`** — the runtime scheme (bg/fg/selection/cursor + 16
  ANSI ints) plus the color-conversion helpers (`toJediColor`, `toTerminalColor`,
  `toAwtColor`, `toRgbInt`, `toHex`, `hexToRgb`) used everywhere colors cross a
  boundary (AWT ↔ JediTerm ↔ hex strings).
- **`TerminalSchemes.kt`** — the built-in templates (Classic Dark, Light,
  Solarized Dark/Light, Monokai, One Dark), the `names()` list for the combo, and
  resolution: `active()` → `byName(...)` → a template, the custom scheme, or
  `fromIde()` (derives colors live from the IDE editor scheme, picking dark/light
  ANSI by `ColorUtil.isDark`).
- **`SchemeColorPalette.kt`** — a JediTerm `ColorPalette`: indices 0–15 from the
  scheme, 16–231 the xterm 6×6×6 cube, 232–255 the 24-step grayscale ramp.

---

## `ui/` — the Swing layer

### `SshSessionsPanel.kt` — the main panel (largest file)
The left tool window's content: a `Tree` of folders/sessions with a toolbar,
context menu, in-place rename, speed search, and a custom renderer.

- **`treeModel`** is an anonymous `DefaultTreeModel` whose `valueForPathChanged`
  is overridden to **commit an in-place rename through the service** (deferred via
  `SwingUtilities.invokeLater`) instead of replacing the node's user object — see
  doc 02 §6.
- **Construction (`init`)** sets the renderer; enables editing with a custom
  `SessionTreeCellEditor`; installs `TreeSpeedSearch` (type-to-find by name);
  registers `RenameAction` on **Shift+F6**; installs the context menu; wires
  double-click → connect; builds the toolbar via `ToolbarDecorator` (add/edit/
  remove + Connect/NewFolder/Import/Export/OpenInEditor toggle/TerminalColors);
  subscribes to `SshSessionService.TOPIC` → `reload()`.
- **Tree building** — `reload()` rebuilds from `service.foldersIn("")`/
  `sessionsIn("")` recursively, then expands all rows.
- **Selection helpers** — `selectedNode/Session/Folder`, `targetFolderId` (where
  new items go), and `selectSession`/`selectFolder` (+ `findSessionNode`/
  `findFolderNode`) to reselect by id after a rebuild.
- **Actions** — `addSession`/`editSelected`/`removeSelected`/`connectSelected`,
  `addFolder`, `import/exportSessions`, and the appearance ops `setColorSelected`
  (opens `ColorChooser`, writes via the service) / `resetColorSelected`. The
  toolbar/menu actions are inner `DumbAwareAction`s (each declares
  `getActionUpdateThread() = EDT` and an `update` that enables based on selection).
- **`SessionTreeCellEditor`** — `AbstractCellEditor` + `TreeCellEditor`; a
  `JBTextField` that commits on Enter and cancels on Esc; **`isCellEditable`
  returns true only for a `null` event** so editing starts only programmatically.
- **`SessionTreeCellRenderer`** — folders use `AllIcons.Nodes.Folder` (tinted via
  `IconUtil.colorize` when a color is set); sessions use `SessionIcons.icon(...)`
  always tinted (chosen color, or the default blue); the chosen color also colors
  the name (`nameAttributes`, skipped when the row is selected for contrast); the
  grayed `user@host:port [via N jump]` suffix is appended after the name.

### `SshSessionDialog.kt` — create/edit a session
A `DialogWrapper`. Builds the form with `FormBuilder` (name/host/port/user/auth/
password/key/passphrase, store-credentials checkbox, **Icon** combo + **Color**
`ColorPanel` with a *Default* reset, jump-host list via `ToolbarDecorator`).
`updateAuthFields` enables password vs. key fields. `doValidate` enforces host/
user/key-path. **`persist(service)`** writes non-secret fields (incl. `iconKey`/
`color`) via `upsertSession`, then `persistSecrets` writes password/passphrase
(and per-jump-host secrets) to PasswordSafe — or nulls them when *store
credentials* is off — and clears leftover jump-host secrets (the 16-slot loop).

### `JumpHostDialog.kt`
`JumpHostEntry` bundles a `JumpHost` with its in-memory secrets while editing. The
dialog edits one hop; `doOKAction` writes the fields back into the entry (secrets
kept in memory until the parent session is persisted).

### `ExportOptionsDialog.kt`
Asks how many sessions to export and whether to include secrets, with a prominent
**plaintext** warning. Exposes `includeSecrets`.

### `TerminalAppearanceConfigurable.kt`
The *Settings → Tools → HaifaSSH Terminal* page (`Configurable`). A scheme combo,
*Copy to Custom*, `ColorPanel`s for bg/fg/selection/cursor + a 16-cell ANSI grid,
and a live `SchemePreview` (paints a sample shell line, colored tokens, and a
selection block). `isModified/apply/reset` follow the standard `Configurable`
contract; `apply` saves and calls `fireSchemeChanged()` so open terminals repaint.
Editors are enabled only for the **Custom** scheme.

### `SessionIcons.kt` (0.5.0)
Registry of the 8 built-in monochrome session icons (`/icons/sessions/*.svg`),
each a `Choice(key, label, icon)`. `choice(key)` / `icon(key)` resolve a key (or
fall back to `server`). The icons are flat single-color silhouettes so
`IconUtil.colorize` tints them cleanly.

### `AppearanceColors.kt` (0.5.0)
Color helpers: `DEFAULT_SESSION_TINT` (the blue used when no color is chosen), a
small `PALETTE`, and `parse(hex)` / `toHex(color)` (`#RRGGBB` ↔ `Color` via
`ColorUtil`). Used by the renderer, the dialog, and the color actions.

---

## `resources/icons/`

- `sshSessions.svg`, `sshTerminals.svg` — tool-window stripe icons, redrawn as
  clean monochrome `#6C707E` so the New UI recolors them like bundled icons.
- `sessions/*.svg` — the 8 tintable session icons (server, database, cloud,
  terminal, globe, container, laptop, lock).
- `META-INF/pluginIcon.svg` — the plugin's Marketplace/Plugins-list icon.

---

## How a change ripples through the code (cheat sheet)

| You want to… | Touch these |
|--------------|-------------|
| Add a stored session field | `model/SshSession` (+`copyOf`), `SshSessionExport`, both halves of `SshSessionTransfer`, the dialog (`SshSessionDialog`), maybe the renderer. |
| Add a tree action | An inner `DumbAwareAction` in `SshSessionsPanel`, add to the `ToolbarDecorator` and/or `installContextMenu`. |
| Add a terminal color option | `terminal/theme/*` + `TerminalAppearanceConfigurable` + `HaifaSshSettings.StoredScheme`. |
| Change connection behavior | `ssh/SshConnector` (+ `HopConfig` if a new per-hop input is needed) and `SshTerminalManager.buildChain/resolveHop`. |
| Register a new platform hook | `META-INF/plugin.xml` `<extensions>` + the implementing class. |
