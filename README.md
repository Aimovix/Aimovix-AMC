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
AMC_REF=v1.2.4 bash setup-amc.sh
```

To test a development branch with local files, clone that branch and run its local installer:

```bash
git clone --branch <branch-name> https://github.com/Aimovix/Aimovix-AMC.git
bash Aimovix-AMC/termux-bridge/setup.sh
```

Setup installs Python, Git, curl, jq, Termux:API tools, the pinned Python dependency, and the `amc` service manager. The bridge listens only on `127.0.0.1:8765`.

### 4. Pair the app

```bash
amc token
```

Enter that token in **AMC → Setup → Step 4**, then select **Connect now**. Authentication is mandatory for every connection, including other apps on the same phone. Keep the token private. It is not printed in daemon logs or ordinary status output.

### 5. Configure an AI provider

In **Settings**, choose a provider, enter a model ID and API key, then save. You can switch models from the chat toolbar. For local inference, configure an OpenAI-compatible server at `http://127.0.0.1:8080/v1`.

Model suggestions are editable examples, not a live availability catalog. Check your provider account for supported models and pricing. A model must support the features used by your task, such as images or tool calls.

## Using AMC

- **Chat:** submit a task, attach an image, or use a quick action.
- **Step-by-step mode:** the default for new installations; every agent command needs approval.
- **Autopilot:** recognized diagnostic commands can run automatically. File changes, network operations, scripts, and unknown commands normally require approval.
- **Strict mode:** requires approval for every agent command, even operations covered by a custom allowlist.
- **Emergency stop:** requests cancellation through the active bridge connection. The bridge handles controls while commands run and terminates the command's process group. The UI reports a request rather than claiming immediate completion.
- **Terminal:** manually enter commands and use `CTRL-C` to interrupt them. Commands entered here are direct user actions; the catastrophic blocklist still applies.
- **Sessions:** create, search, switch, and export chats as Markdown or JSON. User-authored history and existing generated content are preserved as entered.
- **Artifacts:** view supported images, text, code, Markdown, and HTML. Script execution returns to the agent flow for review.
- **Voice input:** uses English speech recognition when the device's recognition service supports it.

## Providers and local inference

| Provider | Connection | Notes |
| --- | --- | --- |
| Google Gemini | Gemini API | Streaming and function calls |
| OpenAI | Chat Completions API | Streaming, tool calls, and compatible image models |
| Anthropic Claude | Messages API | Streaming and supported image inputs |
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
- Untrusted tool output is marked for the model. These markers and prompt instructions are precautions, not an isolation mechanism.

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
    agent/                     Agent loop and system prompt
    data/                      Models, networking, repositories, storage
    security/                  Command assessment and approval policy
    service/                   Foreground service and WorkManager
    ui/                        Chat, terminal, setup, settings, components
  res/                         Android resources and network policy
android/app/src/test/          Kotlin unit and integration tests
termux-bridge/                 Python bridge, integration tests, CLI, setup
SECURITY.md                    Boundaries, signing migration, recovery
```
