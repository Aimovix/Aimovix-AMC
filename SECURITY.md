# Security and release-signing migration

AMC is experimental software that executes commands with Termux's permissions. Do not treat its shell filter or AI instructions as a sandbox.

## Exposed historical signing identity

Earlier repository revisions contain a release keystore and its passwords. Treat that signing identity as compromised even after the file is removed from the current branch.

This change removes the keystore, removes embedded passwords, ignores private key files, separates debug signing, and supports external release-signing configuration. It does not erase Git history, remove existing release assets, or rotate a store-managed key.

Before distributing a new release:

1. Create a new keystore outside the repository and keep an encrypted backup.
2. Configure `AMC_KEYSTORE_FILE`, `AMC_KEYSTORE_PASSWORD`, `AMC_KEY_ALIAS`, and `AMC_KEY_PASSWORD` in the local release environment or an appropriately protected CI secret store.
3. Identify how the existing app was distributed and whether the exposed key was an upload key or an app-signing key.
4. For a store-managed app, use that store's supported key-reset or key-upgrade process. An upload-key reset alone does not change an exposed app-signing key.
5. For directly distributed APKs, plan compatibility for existing installations. A new signing identity ordinarily cannot update an app signed by another key. Back up/export user data before any uninstall, and communicate the migration clearly. Do not remove an installed app without an explicit decision and backup.
6. Review historical release assets. Do not label an older APK as containing the current security fixes.

History rewriting or deleting old release assets requires a separate coordinated decision because it affects existing clones, links, and users. Removing old files is not a substitute for key replacement.

## Bridge pairing and credential handling

The bridge binds to `127.0.0.1`, authenticates every connection, and rejects requests with browser origins. The token is generated with Python's `secrets` module and stored in `~/.termux_agent_token` with mode `0600`.

Run `amc token` in your own Termux session to display it for pairing. Ordinary daemon logs and `amc status` do not reveal it. Do not paste tokens into issues, chats, screenshots, or shared logs.

If an older version exposed a token in logs or the clipboard, stop the bridge, remove its token file, and restart it to generate a new one. Pair the app again afterward. Review and remove obsolete sensitive logs and clear the clipboard as appropriate.

Android credentials use encrypted preferences. If initialization fails, AMC stops startup and asks the user to unlock the device and retry. It does not create a new plaintext credential store. Legacy plaintext credentials are deleted only after the encrypted migration is committed successfully.

## Command approval

New installations use the **Autonomous** security preset (routine work runs on its own; sensitive actions ask). **Supervised** reviews every action. **Unattended (Turbo)** without its opt-in bypasses interactive approvals except for always-confirm actions while strictly maintaining the hardcoded catastrophic blocklist; with the opt-in it is the unattended mode described below. **Full machine** requires command approvals while granting open filesystem access. The **Custom** preset allows users to configure custom allowlists, blocklists, and strict mode. High-risk operations, scripts, unknown commands, and shell composition cannot receive allowlist exceptions. Known catastrophic patterns are always blocked across all presets.

A direct terminal submission is an explicit user command, not an autonomous agent decision. The client still blocks catastrophic patterns. Manually synchronized cron schedules run independently of interactive agent approval; review their contents before installing them.

Approved scripts can access whatever Termux can access. Broad allowlist rules can authorize unintended operations within recognized command families. Prefer exact rules and inspect command arguments. No finite regular-expression filter can prove arbitrary shell code safe.

## Unattended mode

Unattended mode is opt-in: the Unattended (Turbo) preset plus a one-time confirmation dialog that shows the allowed, refused and never-allowed actions (both answers equally large). In this mode nothing waits for a person: a fixed rule per category either allows and logs an action or refuses and logs it, tells the model, and the run continues. Refused by default (and only unlockable by the user, never by the agent): payments and purchases, calls, accounts and logins, app installs from stores.

**Fixed floor, never switchable:** catastrophic shell targets (root, home, storage root), banking, password and authenticator apps (also when the user released them for supervised use), Android security settings (also through Shizuku `settings put secure …`), AMC itself (package, permissions, data, persona, its own security settings) and the bridge token. The bridge token rule (`~/.termux_agent_token`, `amc token`) applies in every mode, also in scripts.

**Prompt injection:** web pages, files, screens, notifications, downloads, sub-agent reports and messages from other Telegram users are data. Once a run has read such data, an action caused by it cannot unlock a refused category, create durable memory entries or lessons, schedules, notification triggers or skills: these need the user's task to ask for them and must not repeat distinctive content (addresses, links, numbers, five words in a row) from the untrusted data. This is a heuristic, not an isolation mechanism.

**Limits and stop:** optional per-run limits (duration, screen actions, messages sent) end a run cleanly with a report. The chat's stop button, the "Stop all agents" notification action and Quick Settings tile, the phone-control banner and `/stop` in Telegram stop every unattended run.

## Audit log

Every tool call is written to the `audit_events` table: time, run, session, trigger, tool, app, arguments (redacted like password fields; one-time codes and tokens masked; calls carrying a secret keep no values), decision with rule and reason, result summary, duration and exit code. Each entry stores the SHA-256 of its content and of the previous entry; the app verifies the chain and names the first broken entry. The agent has no tool that reads, changes or deletes the log. Retention (30/90/365 days, plus a size bound) removes only whole prefixes of the chain, so the rest stays verifiable. The hash chain detects edits by apps or people without root; it cannot stop someone with root access to the device from rewriting the whole database.

## Process control

Each authenticated connection owns its execution task and working directory. The receive loop remains available for pings and interrupts while execution runs. Cancellation sends signals to the command's process group, escalates when necessary, and waits for cleanup. Timeouts and disconnects also stop active tasks.

Detached processes that create a separate session, scheduled cron jobs, and completed side effects are outside the chat stop button's scope. Do not describe cancellation as undo.

## Reporting

Do not post credentials or working exploits with private data in public issues. Share a minimal reproduction, affected commit, expected behavior, and sanitized logs through an appropriate private channel agreed with the maintainer.
