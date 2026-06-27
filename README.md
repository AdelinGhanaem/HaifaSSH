# HaifaSSH — SSH Session Manager for JetBrains IDEs

A MobaXterm-style SSH workflow embedded in IntelliJ-based IDEs: organize SSH
sessions in a folder tree, connect to an interactive terminal in one click, and
keep credentials in the IDE Password Safe.

## Features (v0.5.0)

- **Session tree** — folders + sessions in the left-hand *SSH Sessions* tool window.
- **Customizable appearance** — give each session a built-in **icon** (server, database,
  cloud, terminal, globe, container, laptop, secure) and a **color**; color folders too.
  The color tints both the icon and the name in the tree. Set it from the session dialog,
  or right-click → *Set Color…* / *Reset Color* (works for folders and sessions).
- **In-place rename** — press **Shift+F6** on any folder or session to rename it right in
  the tree, just like renaming a symbol; **Esc** cancels.
- **Quick search** — start typing in the tree to find and jump to a session/folder by name.
- **Embedded SSH terminals** — double-click a session (or use the ▶ toolbar button)
  to open a real interactive shell (powered by
  [JediTerm](https://github.com/JetBrains/jediterm) + JSch).
- **Terminal opens as an editor tab** — by default the shell opens in the editor area,
  right where source files open. Toggle the *Open Terminals in Editor Tab* button in the
  *SSH Sessions* toolbar to fall back to the bottom *SSH Terminals* tool window instead.
- **Terminal color schemes** — pick a ready-made template (Classic Dark, Solarized Dark/Light,
  Monokai, One Dark, Light), **Follow IDE Theme**, or a fully editable **Custom** scheme
  (background, foreground, selection, cursor, and all 16 ANSI colors) under
  *Settings → Tools → HaifaSSH Terminal* (or the palette button in the *SSH Sessions* toolbar).
  Use *Copy to Custom* to start from a template and tweak it.
- **Import / export** — back up or share your whole session tree via the toolbar
  *Import*/*Export* buttons. Configuration (hosts, folders, jump hosts) is always
  exported; passwords/passphrases are included only if you tick the option (the file
  then holds plaintext secrets). Import always *merges* — it never overwrites existing
  sessions.
- **Secure credentials** — passwords and key passphrases live in the IDE
  [Password Safe](https://www.jetbrains.com/help/idea/passwords.html), never in the
  session XML. Untick *Store credentials* to be prompted on each connect.
- **Authentication** — password or private-key auth.
- **Jump hosts** — chain through one or more bastion hosts (ProxyJump-style),
  each with its own credentials.

## Requirements

- JetBrains IDE **2024.2+** (build 242+)
- JDK **17** to build

## Build & run

```bash
# Compile + package the installable plugin zip (build/distributions/*.zip)
./gradlew buildPlugin

# Launch a sandbox IDE with the plugin installed
./gradlew runIde
```

### Installing from disk

After `buildPlugin`, install this file:

```
build/distributions/haifassh-0.5.0.zip
```

In the IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, then select
that **`.zip`**. Pick the ZIP itself — do **not** unzip it, and do **not** choose
anything under `build/tmp/buildSearchableOptions/` (those are intermediate index
files that are already bundled inside the ZIP).

> The build prints warnings from a headless sandbox IDE during
> `buildSearchableOptions` (marketplace fetch errors, `WindowMove`/JCEF notices,
> etc.). These are harmless and do not affect the produced ZIP.

## Usage

1. Open the **SSH Sessions** tool window (left).
2. Click **+** to add a session (host, port, user, auth, optional jump hosts);
   use the folder button to group sessions.
3. **Double-click** a session — its shell opens as an **editor tab** (or in the bottom
   *SSH Terminals* tool window if you turn off *Open Terminals in Editor Tab*).
4. Use the toolbar **Import**/**Export** buttons to move sessions between machines.

## Architecture

| Layer | Key types |
|-------|-----------|
| Model | `model/SshSession`, `SshFolder`, `JumpHost` |
| Persistence | `settings/SshSessionService` (PersistentStateComponent), `SshCredentialStore` (PasswordSafe), `HaifaSshSettings` (prefs) |
| Import/export | `settings/SshSessionTransfer`, `SshSessionExport` (portable bundle) |
| SSH | `ssh/SshConnector` (JSch + jump-host port forwarding), `SshShellHandle` |
| Terminal | `terminal/SshTtyConnector` (JediTerm ↔ SSH PTY bridge), `SshTerminalManager`, `SshTerminalUi`, `IdeTerminalSettingsProvider` |
| Terminal theme | `terminal/theme/TerminalColorScheme`, `SchemeColorPalette` (16-color ANSI), `TerminalSchemes` (templates + Follow-IDE) |
| Terminal-in-editor | `terminal/editor/SshTerminalFileEditor(Provider)`, `SshTerminalVirtualFile` |
| UI | `ui/SshSessionsPanel` (tree + toolbar), `SshSessionDialog`, `JumpHostDialog`, `ExportOptionsDialog`, `TerminalAppearanceConfigurable` (color settings) |

## Roadmap

- SFTP file browser for the active session
- ssh-agent / `~/.ssh/config` import
- Known-hosts verification (currently `StrictHostKeyChecking=no`)
- Per-session terminal color schemes & startup commands
