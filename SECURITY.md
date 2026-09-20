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

New installations use the **Default** security preset (manual review for all terminal commands). **Turbo mode** bypasses interactive approvals for maximum speed while strictly maintaining the hardcoded catastrophic blocklist. **Full machine** requires command approvals while granting open filesystem access. The **Custom** preset allows users to configure custom allowlists, blocklists, and strict mode. High-risk operations, scripts, unknown commands, and shell composition cannot receive allowlist exceptions. Known catastrophic patterns are always blocked across all presets.

A direct terminal submission is an explicit user command, not an autonomous agent decision. The client still blocks catastrophic patterns. Manually synchronized cron schedules run independently of interactive agent approval; review their contents before installing them.

Approved scripts can access whatever Termux can access. Broad allowlist rules can authorize unintended operations within recognized command families. Prefer exact rules and inspect command arguments. No finite regular-expression filter can prove arbitrary shell code safe.

## Process control

Each authenticated connection owns its execution task and working directory. The receive loop remains available for pings and interrupts while execution runs. Cancellation sends signals to the command's process group, escalates when necessary, and waits for cleanup. Timeouts and disconnects also stop active tasks.

Detached processes that create a separate session, scheduled cron jobs, and completed side effects are outside the chat stop button's scope. Do not describe cancellation as undo.

## Reporting

Do not post credentials or working exploits with private data in public issues. Share a minimal reproduction, affected commit, expected behavior, and sanitized logs through an appropriate private channel agreed with the maintainer.
