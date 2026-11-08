# AMC — AI Mobile Center by Aimovix

AMC is a native Android app that connects an AI assistant to a local Termux environment. Ask it to inspect the device, work with files, run scripts, or use supported Termux:API features. The interface, built-in prompts, logs, examples, and documentation are in English.

**Status: experimental.** AMC can execute commands with Termux's permissions. Its command filter reduces accidental execution; it is not a sandbox or a guarantee against prompt injection. Review commands before granting access to private data or device features.

> **Signing migration:** earlier repository revisions included a release keystore and its passwords. That identity must be treated as compromised. This revision removes it and uses external signing configuration. See [SECURITY.md](SECURITY.md) before distributing an APK or upgrading an existing installation.

## Quick start

### 1. Build or obtain an APK

The CI workflow builds a debug APK for testing. Download `aimovix-debug-apk` from a successful [Actions run](https://github.com/Aimovix/Aimovix-AMC/actions), or build locally:

```bash
cd android
./gradlew assembleDebug
```

Debug builds use the standard local debug signing key. They are development artifacts, not production releases. Older [release assets](https://github.com/Aimovix/Aimovix-AMC/releases) do not automatically include the changes on the current branch.

### 2. Install Termux and Termux:API

Install [Termux](https://f-droid.org/packages/com.termux/) and [Termux:API](https://f-droid.org/packages/com.termux.api/) from F-Droid. Use the same installation source for both. Grant only the Android permissions needed for your tasks.

### 3. Install the bridge

Run this in Termux after reviewing the setup script:

```bash
curl --fail --show-error --location \
  https://raw.githubusercontent.com/Aimovix/Aimovix-AMC/main/termux-bridge/setup.sh \
  -o setup-amc.sh
less setup-amc.sh
bash setup-amc.sh
```

The installer downloads from `main` by default. To pin a specific release tag or branch:

```bash
AMC_REF=v1.5.0 bash setup-amc.sh
```

To test a development branch with local files, clone that branch and run its local installer:

```bash
git clone --branch <branch-name> https://github.com/Aimovix/Aimovix-AMC.git
bash Aimovix-AMC/termux-bridge/setup.sh
```

Setup installs Python, Git, curl, jq, Termux:API tools, the pinned Python dependency, and the `amc` service manager. The bridge listens only on `127.0.0.1:8765`.

### 4. Pair the app

Open **AMC → Setup → Quick connect**, allow AMC to run commands in Termux, then tap **Auto-connect**. AMC asks Termux (through Termux's official `RUN_COMMAND` interface) to start the bridge and hand over the token; nothing has to be copied. From then on AMC also restarts the bridge by itself whenever it is not reachable (app, background tasks and the Telegram channel).

The installer enables `allow-external-apps = true` in `~/.termux/termux.properties`, which `RUN_COMMAND` requires (opt out with `AMC_ALLOW_EXTERNAL_APPS=0`). Only apps you grant the Termux "Run commands in Termux environment" permission can use it. For older installations, Quick connect shows the one-line command to enable it.

Manual pairing still works:

```bash
amc token
```

Enter that token in **AMC → Setup → Step 4**, then select **Connect now**. Authentication is mandatory for every connection, including other apps on the same phone. Keep the token private. It is not printed in daemon logs or ordinary status output.

### 5. Configure an AI provider

In **Settings**, choose a provider, enter a model ID and API key, then save. You can switch models from the chat toolbar. For local inference, configure an OpenAI-compatible server at `http://127.0.0.1:8080/v1`.

Model suggestions are editable examples, not a live availability catalog. Check your provider account for supported models and pricing. A model must support the features used by your task, such as images or tool calls.

## Autonomous agent

AMC works like a self-hosted personal agent in the spirit of OpenClaw or OpenCrabs, but entirely on your phone: give it a goal and it plans, acts, checks the results and keeps going until the task is done. You can reach it in the app or, through the optional Telegram channel, from anywhere.

- **Agent loop:** the model calls tools, reads the results, recovers from errors (installing missing packages, trying a different approach) and finishes with a summary. The step budget is configurable (**Settings → Autonomy**, 5–100 steps, default 30). After repeated failures the agent is told to rethink its approach; real loops are stopped.
- **Tools:** `execute_command` (Termux shell and Termux:API, optional timeout up to 30 minutes), `read_file`, `write_file`, `web_search` (DuckDuckGo, no API key), `web_fetch` (native HTTP, HTML converted to text), `remember` / `forget` (long-term memory), `search_history` (earlier conversations), `delegate_task` (sub-agents), `send_file` (deliver files to you), `update_plan` (live checklist in the chat), `notify_user` (Android notification), and `schedule_task` / `list_scheduled_tasks` / `cancel_scheduled_task`.
- **Sub-agents:** for research or long multi-file work the agent hands a self-contained sub-task to a sub-agent with a fresh context and gets back its report. The sub-agent's steps appear in the tool card, its approvals are forwarded to you, and it cannot delegate further.
- **Persona (SOUL.md):** in **Settings → Autonomy → Persona & standing instructions** you describe who the agent should be and how it should work for you (name, tone, language, defaults, things to always or never do). It applies everywhere and never overrides the security and legal rules.
- **Long-term memory:** the agent saves stable facts about you (name, preferences, devices, recurring tasks) and sees them in every new conversation. View, edit or clear it in **Settings → Autonomy**. Secrets are never meant to be stored there.
- **Scheduled agent tasks:** ask, for example, *"Remind me every day at 8 to take my medication"* or *"Check the weather every two hours and tell me if it will rain"*. The agent schedules itself; each background run gets its own chat session and ends with a notification.
- **Heartbeat:** optional periodic check-in (15 minutes to 12 hours). The agent works through your checklist and only notifies you when something needs attention; quiet runs leave no trace.
- **Skills:** reusable how-to files in `~/.amc/skills/` (created by the installer), either `<name>.md` or `<name>/SKILL.md` with `name:` and `description:` front matter (the Agent Skills format used by OpenClaw). Every run starts with a list of the installed skills and their descriptions; the agent reads a matching skill before following it and writes new ones after solving something it is likely to need again. Treat downloaded skills like scripts: read them before use.
- **Steering:** you can keep typing while the agent works; the message is queued and picked up before its next step.
- **Your language:** the agent answers in the language you write in, and voice input uses your device language.

## Remote control via Telegram

Talk to the agent on your phone from any device, like OpenClaw's messaging gateways, without running a server:

1. In Telegram, open **@BotFather**, send `/newbot` and copy the bot token.
2. In **AMC → Settings → Channels**, paste the token and select **Check & save**, then switch the **Telegram channel** on.
3. Select **Pair account** and send the shown `/pair CODE` to your bot (or use **Open in Telegram**). Codes are single-use, valid for 15 minutes and expire after 5 wrong attempts.

Then just write to your bot, for example *"How full is my storage?"* or *"Take a photo and send it to me"*. You can send photos with a question, add instructions while the agent works, and use `/new` (new conversation), `/stop` (stop the task), `/status` and `/unpair`. Each Telegram chat continues its own AMC session, visible in the app.

- Only paired Telegram accounts in private chats can reach the agent; group messages and strangers are ignored (strangers get a short pairing hint). The phone notifies you whenever an account is paired.
- Actions that need approval arrive as ✅/❌ buttons. No answer within 10 minutes counts as rejected. Your security preset applies exactly as in the app.
- The channel runs in a foreground service and restarts after a reboot when it is enabled. For reliable delivery while the phone sleeps, set **Android Settings → Apps → AMC → Battery** to **Unrestricted**.
- Messages, replies, approval requests and files pass through Telegram's servers. Use the in-app chat for anything you would not send through Telegram, and keep the bot token secret (anyone with it can read your bot's messages, but only paired accounts can control the agent).

## Using AMC

- **Chat:** submit a task, attach an image, or use a quick action.
- **Security presets:** choose between **Autonomous** (default; routine work runs on its own, while SMS, calls, camera, location, clipboard, deleting files and killing processes still ask first), **Supervised** (every action needs approval), **Full machine** (manual review for commands, full device file access), **Turbo mode** (no approval prompts at all; only catastrophic commands stay blocked), and **Custom** (granular control with strict mode, custom allowlists, and blocklists). Background runs can never approve themselves: actions that need approval are skipped and listed in the result notification.
- **Strict mode:** available in Custom preset; requires approval for every agent command, even operations covered by a custom allowlist.
- **Emergency stop:** requests cancellation through the active bridge connection. The bridge handles controls while commands run and terminates the command's process group. The UI reports a request rather than claiming immediate completion.
- **Terminal:** manually enter commands and use `CTRL-C` to interrupt them. Commands entered here are direct user actions; the catastrophic blocklist still applies.
- **Sessions:** create, search, switch, and export chats as Markdown or JSON. User-authored history and existing generated content are preserved as entered.
- **Artifacts:** view supported images, text, code, Markdown, and HTML. Script execution returns to the agent flow for review.
- **Voice input:** uses the device language for speech recognition when the device's recognition service supports it.

## Providers and local inference

| Provider | Connection | Notes |
| --- | --- | --- |
| Google Gemini | Gemini API | Streaming and function calls |
| OpenAI | Chat Completions API | Streaming, tool calls, and compatible image models |
| Anthropic Claude | Messages API | Streaming, tool use, image inputs, prompt caching of the system prompt and tools |
| Groq | OpenAI-compatible API | Model capabilities vary |
| OpenRouter | OpenAI-compatible API | Model and provider capabilities vary |
| Local server | OpenAI-compatible HTTP endpoint on loopback | Requires a separately running inference server |

An optional secondary provider can handle retryable failures such as rate limits and server errors. Token counts and cost estimates are informational; provider billing is authoritative.

To install and run a local model from Termux:

```bash
bash ~/.termux_agent/local_model_manager.sh
```

The model manager downloads a GGUF model and starts `llama-server` on loopback port 8080. Downloads require internet access and storage; inference can run locally after installation. Memory and performance depend on the device and model.

## Background operation

Set **Android Settings → Apps → Termux → Battery** to **Unrestricted** or **Not optimized**, and allow notifications. Leave Termux running in the background rather than exiting or force-stopping it.

The service uses a wake lock, a notification, and startup hooks. These improve reliability but cannot guarantee that Android or manufacturer-specific limits will keep a process alive. Termux:Boot must be installed separately if you want startup after a device reboot.

```bash
amc start       # Start the bridge
amc stop        # Stop the service and active connection tasks
amc restart     # Restart the bridge
amc status      # Show service status without revealing the token
amc token       # Display the private pairing token explicitly
amc logs        # Follow daemon logs
amc boost       # Open background settings and restart the service
amc autostart   # Configure shell and Termux:Boot hooks
amc run         # Run in the foreground
```

### Scheduled work

**WorkManager** supports one-time and recurring command jobs with network and charging constraints. Before execution, a worker loads the stored security rules, checks whether approval is required, waits for authenticated connection, and releases the connection when finished. Jobs needing approval are rejected with a notification; they cannot silently approve themselves.

**Termux crontab** is a separate, manually managed scheduler. Synchronizing a crontab installs the user's supplied schedule. Cron commands subsequently run outside AMC's interactive approval flow and are not stopped by the chat's emergency-stop control. Inspect every entry before syncing, and remove entries explicitly when no longer needed.

## Legal

On first launch AMC shows its terms of use, disclaimer and privacy notice (English and German); the app can only be used after all three confirmations are ticked, and the texts can be reread in **Settings → Legal & privacy**. Raising `LegalTexts.VERSION` asks every user to accept a changed version.

- Actions that involve other people or cost money (sending SMS, phone calls, reading messages, contacts or call logs, camera photos, microphone recordings) **always** require your confirmation, in every security mode including Turbo and Custom allowlists (in the app, or with buttons in your paired Telegram chat), and are never performed in unattended background runs.
- The agent's system prompt refuses clearly illegal tasks (spam, harassment, covert surveillance, unauthorized access, piracy) and treats other people's data as personal data.
- Documents: [terms](docs/legal/TERMS.md) ([German](docs/legal/TERMS.de.md)), [privacy notice](docs/legal/PRIVACY.md) ([German](docs/legal/PRIVACY.de.md)), [third-party notices](docs/legal/THIRD_PARTY_NOTICES.md), and a note on when a German imprint is required.
- AMC is a private, non-commercial open-source hobby project with no income. It is not affiliated with or endorsed by any AI provider or by Termux. Contact: GitHub issues.
- License: [MIT](LICENSE). The software is provided "as is", without warranty.
- These texts are not legal advice. If the project ever earns money (sales, ads, sponsorships), revisit the imprint note in [IMPRESSUM.de.md](docs/legal/IMPRESSUM.de.md).

## Security boundaries

- The bridge is device-local, requires a token, and rejects browser origins.
- Each connection has its own working directory, execution task, and process reference.
- Commands have a server-side timeout. Disconnecting the client cancels that connection's active task.
- Known catastrophic command patterns are blocked. The filter conservatively classifies unknown commands and shell composition as high risk.
- Custom allowlist patterns must match the entire recognized command. They cannot bypass high-risk or catastrophic classification, strict mode, or step-by-step mode.
- API keys and tokens use `EncryptedSharedPreferences`. If encrypted storage cannot initialize, the app shows a recovery screen instead of writing plaintext credentials.
- Legacy plaintext preference values are cleared only after a successful encrypted migration.
- Android backup is disabled for the app.
- External provider connections use HTTPS; cleartext connections are limited by the Android network configuration to approved local endpoints.
- Untrusted tool output (shell, files, web pages, search results, sub-agent reports) is marked for the model. These markers and prompt instructions are precautions, not an isolation mechanism.
- The Telegram channel only serves Telegram accounts paired with a one-time code, only in private chats. The bot token and paired accounts are stored in encrypted preferences. Approvals from Telegram are bound to the chat they were sent to and ignored from other accounts.

Once a command is approved, it runs with Termux's permissions. Programs may start detached jobs or change their own process groups. Review arbitrary scripts carefully; process cancellation cannot undo an SMS, file change, network request, or other completed effect. See [SECURITY.md](SECURITY.md) for signing and credential recovery.

## Development

Requirements: Android SDK Platform 35, a compatible Android Studio installation, and JDK 21 for the repository's CI configuration. The Gradle wrapper is included. The app targets Android 8.0 (API 26) and later.

```bash
cd android
./gradlew testDebugUnitTest assembleDebug --no-daemon
```

Bridge integration tests run on Linux with Python 3.10 or later:

```bash
python -m pip install -r termux-bridge/requirements.txt
python -m unittest discover -s termux-bridge -p 'test_*.py' -v
bash -n termux-bridge/setup.sh termux-bridge/amc termux-bridge/local_model_manager.sh
```

The GitHub Actions workflow runs Android unit tests, builds the debug APK, runs bridge integration tests, and checks shell syntax. Bridge tests cover authentication, browser-origin rejection, controls during execution, timeouts, disconnect cleanup, connection isolation, and large streamed output.

### Release signing

Create and protect a **new** signing identity outside the repository. Supply:

```text
AMC_KEYSTORE_FILE       Absolute path to your private keystore
AMC_KEYSTORE_PASSWORD   Keystore password
AMC_KEY_ALIAS          Signing key alias
AMC_KEY_PASSWORD       Signing key password
```

Then run `./gradlew assembleRelease` from `android/`. Without all four values, the release build is unsigned. Never distribute an unsigned build as an installable release, commit credentials, or reuse the exposed historical key. Debug builds always use the standard debug identity.

Changing a signing identity affects upgrades. Follow the migration guidance in [SECURITY.md](SECURITY.md) before replacing an installed app or publishing a release.

## Project layout

```text
.github/workflows/             Android and bridge CI
android/app/src/main/
  java/com/agent/mobile/
    agent/                     Agent loop, tools, prompts, memory, persona, skills index
    channel/                   Telegram channel gateway, foreground service, boot receiver
    data/                      Models, networking, repositories, storage
    security/                  Command assessment and approval policy
    service/                   Foreground service and WorkManager
    ui/                        Chat, terminal, setup, settings, components
  res/                         Android resources and network policy
android/app/src/test/          Kotlin unit and integration tests
termux-bridge/                 Python bridge, integration tests, CLI, setup
SECURITY.md                    Boundaries, signing migration, recovery
```
