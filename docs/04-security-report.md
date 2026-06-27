# HaifaSSH — Security Report

**Subject:** HaifaSSH SSH Session Manager plugin (v0.5.0)
**Scope:** the plugin source under `src/main/kotlin/com/haifasoft/haifassh/` and
its dependencies, as of this review.
**Question answered:** *Can this plugin be secured, and what security issues does
it have?*

> **Bottom line:** Yes — the plugin **can be secured**. Its credential
> architecture is already sound (secrets in PasswordSafe, separated from config).
> The headline risk is that it **disables SSH host-key verification**
> (`StrictHostKeyChecking=no`), which exposes every connection to
> man-in-the-middle attacks. That one issue, plus re-enabled legacy crypto and
> plaintext credential export, are the items to fix. None require an
> architectural rewrite.

---

## 1. Methodology & threat model

This is a **developer tool** installed locally into a JetBrains IDE. The user owns
the sessions and the machine. The realistic threats are therefore:

- **T1 — Network attacker (MITM):** intercepts/relays SSH traffic on an untrusted
  network, impersonating a server.
- **T2 — Local file exposure:** another user/process/backup/sync reads files the
  plugin writes (exports, config).
- **T3 — Local memory exposure:** secrets recoverable from heap dumps / swap.
- **T4 — Supply chain:** a compromised or vulnerable third-party dependency.
- **T5 — Code-level injection:** attacker-controlled data reaching a sensitive sink.

Review was a manual source audit (no dynamic testing). Severity uses
High/Medium/Low/Info with a developer-tool lens.

---

## 2. Findings summary

| # | Severity | Finding | Threat | Location |
|---|----------|---------|--------|----------|
| F1 | **High** | SSH host-key verification disabled (`StrictHostKeyChecking=no`); no `known_hosts`, no fingerprint check | T1 | `ssh/SshConnector.kt:40` |
| F2 | **Medium** | Legacy weak algorithms re-enabled (`ssh-rsa`/SHA-1, `ssh-dss`/DSA) | T1 | `ssh/SshConnector.kt:72–86` |
| F3 | **Medium** | Export can write **plaintext** passwords/passphrases to an unencrypted file | T2 | `ui/ExportOptionsDialog.kt`, `settings/SshSessionTransfer.kt:24–32` |
| F4 | **Low** | Secrets handled as immutable `String` in memory (cannot be wiped) | T3 | `ui/SshSessionDialog.kt`, `ui/JumpHostDialog.kt`, `terminal/SshTerminalManager.kt` |
| F5 | **Low** | Exported file written with default filesystem permissions | T2 | `settings/SshSessionTransfer.kt:32` |
| F6 | **Low** | Per-session secret cleanup bounded to 16 jump hosts | T2/T3 | `settings/SshCredentialStore.kt:36`, `ui/SshSessionDialog.kt` |
| F7 | **Info** | PasswordSafe strength depends on user-chosen backend | T2/T3 | `settings/SshCredentialStore.kt` |
| F8 | **Info** | No `until-build` cap → runs on untested future platforms | — | `build.gradle.kts:47` |
| F9 | **Info** | Pinned deps: JSch fork `0.2.25` (good), JediTerm `3.53` (older, pinned for Kotlin metadata) | T4 | `build.gradle.kts:27–36` |

### Positives confirmed (already good)

- **Secrets are not stored in config XML** — they go to `PasswordSafe`
  (`SshCredentialStore`), keyed per session. Deleting a session clears its
  secrets.
- **No secret logging.** Error/notification messages include host/display name but
  never passwords (`SshTerminalManager.notifyError`).
- **No command/shell injection.** Host/user/port are passed to `jsch.getSession`
  as discrete arguments — never interpolated into a shell string (T5 not present).
- **Jump-host forwards bind to loopback only** (`setPortForwardingL("127.0.0.1",
  0, …)`), not exposed to the LAN.
- **Import is merge-only** with fresh UUIDs — it cannot silently overwrite
  existing sessions.
- **Connection hardening basics**: 30 s connect timeout and 30 s keep-alive.
- **Plaintext export is opt-in and clearly warned** in the UI.

---

## 3. Detailed findings

### F1 — Host-key verification disabled (High)

```kotlin
session.setConfig("StrictHostKeyChecking", "no")   // SshConnector.kt:40
```

Every hop is connected with strict host-key checking off and **no `known_hosts`
file configured**, so JSch accepts *any* server key without verification or
warning. On an untrusted network (T1), an attacker who can redirect traffic can
present their own key, complete the handshake, and **relay or capture the entire
session — including the password** the user types (or that the plugin sends). This
also defeats the protection password-auth would otherwise get from a verified host
key. The README roadmap already acknowledges this ("Known-hosts verification
(currently `StrictHostKeyChecking=no`)").

**Impact:** full session compromise / credential theft against a network-positioned
attacker. This is the single most important issue.

**Remediation (recommended):** implement **trust-on-first-use (TOFU)** like
OpenSSH:
- Configure a `known_hosts` file: `jsch.setKnownHosts(path)` (e.g. under the
  plugin config dir, or reuse `~/.ssh/known_hosts`).
- Set `StrictHostKeyChecking=ask` and provide a `UserInfo`/`HostKeyRepository`
  that, on an unknown key, shows the fingerprint in a dialog and lets the user
  accept (and persist) or reject; on a **changed** key, warn loudly and refuse by
  default.
- Optionally allow a per-session "trusted fingerprint" field for pinning.

This is the change that moves the plugin from "convenient but unsafe on hostile
networks" to "safe by default."

### F2 — Legacy weak algorithms re-enabled (Medium)

```kotlin
val legacy = listOf("ssh-rsa", "ssh-dss")           // SshConnector.kt:73
// appended to server_host_key and PubkeyAcceptedAlgorithms
```

`ssh-dss` (DSA, effectively 1024-bit) and `ssh-rsa` (RSA with **SHA-1**) are
deprecated and cryptographically weak. They are appended *after* modern algorithms
(so modern is preferred), which limits exposure, but they remain available as a
negotiated fallback and **widen the attack surface / enable downgrade scenarios**,
especially when combined with F1 (no host-key verification). This is a deliberate
compatibility trade-off for old appliances (documented in the code).

**Remediation:** make the legacy fallback **opt-in per session** (a "Allow legacy
SHA-1/DSA algorithms" checkbox) rather than globally on, so the weakening applies
only to the specific old host that needs it, and modern hosts keep a strict
algorithm set. Prefer enabling only `ssh-rsa` (SHA-1) over `ssh-dss` where possible.

### F3 — Plaintext credential export (Medium)

When the user ticks *Include saved passwords & passphrases*, `export` pulls
secrets out of PasswordSafe and writes them **inline, in cleartext**, into the
chosen XML file (`SshSessionTransfer.export` → `toExport(includeSecrets=true)`;
`Files.writeString` at `SshSessionTransfer.kt:32`).
The file is a normal, unencrypted file on disk (T2). The UI warns clearly, and
it's opt-in, which is why this is Medium rather than High — but the resulting
artifact is a high-value plaintext secret store that can be backed up, synced to
cloud storage, or emailed.

**Remediation (pick one or more):**
- **Encrypt the bundle** with a user-supplied passphrase (e.g. AES-GCM with a
  KDF such as PBKDF2/Argon2) when secrets are included; require the same
  passphrase on import.
- Or **drop inline secrets entirely** and export config only, relying on
  PasswordSafe re-entry on the destination machine.
- At minimum, write the file with **owner-only permissions** (see F5) and remind
  the user to delete it after transfer.

### F4 — Secrets as immutable `String` in memory (Low)

Passwords/passphrases are read from `JBPasswordField` (which yields a `char[]`)
but immediately converted to `String` (e.g. `String(passwordField.password)` in
`SshSessionDialog.persistSecrets`, `JumpHostDialog.doOKAction`, and the prompt path
in `SshTerminalManager.resolveHop`). `String`s are immutable and interned in the
heap until GC, so they **cannot be zeroed** and may surface in heap dumps or swap
(T3).

**Remediation:** keep secrets as `char[]`/`ByteArray` end-to-end and overwrite
them after use; pass them to JSch (`setPassword(byte[])` is supported) without an
intermediate `String`. This is defense-in-depth; on a developer workstation the
practical risk is modest.

### F5 — Export file permissions (Low)

`Files.writeString(target, …)` (`SshSessionTransfer.kt:32`) creates the file with
the process umask defaults, which on shared/multi-user systems can be
group/world-readable. Combined with F3 this can expose secrets to other local
users.

**Remediation:** when writing an export (especially with secrets), create it with
owner-only permissions (`PosixFilePermissions` `rw-------` on POSIX; restrict ACL
on Windows) — ideally create the file empty with the right perms, then write.

### F6 — Secret cleanup bounded to 16 jump hosts (Low)

`SshCredentialStore.clearSession(maxJumpHosts = 16)` and the leftover-cleanup loop
in `SshSessionDialog.persistSecrets` assume ≤16 jump hosts. A session that ever had
more would leave **orphaned secrets** in PasswordSafe after deletion/edit (T2/T3,
minor).

**Remediation:** track the actual jump-host count (or enumerate stored keys) and
clear exactly what exists, rather than a fixed bound.

### F7 — PasswordSafe backend strength (Info)

`SshCredentialStore` correctly uses `PasswordSafe`, but its real protection
depends on the **user's** chosen backend: the OS keychain (strong) vs. a KeePass
file *with no master password* (obfuscation only). The plugin can't force this,
but it's worth surfacing in docs and, optionally, warning if the in-memory/no-
master backend is active.

### F8 — No compatibility upper bound (Info)

`untilBuild = null` (`build.gradle.kts`) is an intentional choice for rolling
internal upgrades, but it means the plugin will load into **future, untested**
platform versions where a changed API could misbehave. Not a security
vulnerability per se; relevant to reliability and to the `testFramework`
`LightVirtualFile` usage (a less-stable API). Mitigate with `verifyPlugin` runs
against new IDE builds before rollout.

### F9 — Dependencies / supply chain (Info)

- **`com.github.mwiede:jsch:0.2.25`** — the right call (the original JCraft JSch is
  abandoned); keep it **updated**, as SSH crypto fixes ship here.
- **JediTerm `3.53`** — pinned older because 3.72 needs Kotlin 2.x metadata
  incompatible with the 2024.2 toolchain. Acceptable, but it **lags upstream
  fixes**; revisit when the target platform's Kotlin moves to 2.x.
- General hygiene: pin versions (done), and consider a dependency-vulnerability
  scan (e.g. OWASP dependency-check / GitHub Dependabot) in CI.

---

## 4. Prioritized remediation roadmap

| Priority | Action | Addresses |
|----------|--------|-----------|
| **1 (now)** | Implement `known_hosts` + TOFU host-key verification (fingerprint prompt, refuse changed keys) | F1 |
| **2** | Make legacy SHA-1/DSA algorithms an opt-in per-session toggle | F2 |
| **3** | Encrypt exported bundles with a passphrase (or drop inline secrets) + owner-only file perms | F3, F5 |
| **4** | Handle in-memory secrets as `char[]` and zero them after use | F4 |
| **5** | Clear exactly the stored jump-host secrets (no fixed 16 bound) | F6 |
| **Ongoing** | Keep JSch/JediTerm updated; run `verifyPlugin` + a dependency scan in CI; document PasswordSafe backend guidance | F7, F8, F9 |

---

## 5. Verdict

**Can it be secured? Yes.** The foundations are good: secrets are kept out of
config and stored in PasswordSafe, there is no injection sink, port forwards are
loopback-bound, and imports are non-destructive. The work needed is **additive,
not structural** — chiefly adding host-key verification (F1), tightening crypto
fallback (F2), and protecting exported secrets (F3). With those addressed,
HaifaSSH would be safe to use on untrusted networks and meet the expectations of a
modern SSH client.
