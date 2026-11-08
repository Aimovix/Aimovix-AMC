# AMC privacy notice

_Version 1. This document is not legal advice._

## 1. Who is responsible

AMC is a private, non-commercial open-source hobby project published as **Aimovix** on GitHub. There is no company and no income. Contact: [GitHub issues](https://github.com/Aimovix/Aimovix-AMC/issues) (do not post personal data or API keys there).

The developers do not receive any of your data: AMC has no servers of its own. For processing inside the app on your device you are in control. For data you send to an AI provider, that provider is responsible under its own privacy policy.

## 2. Data stored on your device

| Data | Purpose | Where | How to delete |
| --- | --- | --- | --- |
| Chats, tool output, audit log | Conversation history, traceability | App database (private app storage) | Delete chats / clear audit log in the app, or uninstall |
| Long-term memory (`MEMORY.md`) | Facts the agent should remember | Private app storage | Settings → Autonomy → Memory → Clear all |
| API keys, bridge token, settings | Connecting to services | Encrypted preferences (Android Keystore, AES-256) | Remove in Settings, or uninstall |
| Scheduled tasks | Background runs you or the agent scheduled | Android WorkManager | Settings → Autonomy, or uninstall |
| Skill files (`~/.amc/skills`) | Reusable instructions | Termux home directory | Delete the files in Termux |

Android app backups are disabled for AMC (`allowBackup=false`). AMC contains no analytics, crash reporting, advertising or tracking SDKs.

## 3. Data sent to third parties

- **AI provider you configure** (e.g. Google Gemini, OpenAI, Anthropic, Groq, OpenRouter): your messages, attached images, the agent's memory, the system prompt and the output of tools the agent runs. Tool output can contain file contents and, if the agent reads them with your confirmation, messages, contacts, call logs, photos or your location. Processing is governed by the provider's terms and privacy policy and may take place outside the EU/EEA. Using a **local model** keeps this data on your device.
- **Websites** the agent opens with `web_fetch` or `curl` receive ordinary requests from your phone, including your IP address.
- **Termux bridge**: runs only on your phone (`127.0.0.1`) and requires a token. No data leaves the device through it.

## 4. Other people's data

Messages, contacts, call logs, photos and recordings contain personal data of other people. AMC always asks for your confirmation before reading or creating such data, in every security mode, and never does so in unattended background runs. You are responsible for having a legal basis (for example their consent) when you process it beyond purely personal or household purposes.

## 5. Permissions

Internet (AI provider, websites), notifications (results and approval requests), microphone (only for voice input, when you start it), foreground service and wake lock (keep the agent running while it works), battery optimization request (reliable background operation). Termux and Termux:API hold their own Android permissions, which you grant separately.

## 6. Your rights

Because the developers do not hold your data, requests about data held by an AI provider must be sent to that provider. Under the GDPR you have the right of access, rectification, erasure, restriction, data portability and objection, and the right to lodge a complaint with a supervisory authority.

## 7. Changes

If this notice changes materially, AMC asks you to accept the new version.
