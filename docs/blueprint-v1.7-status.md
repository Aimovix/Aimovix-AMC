# AMC v1.7.x blueprint: implementation status

Status of the "AMC v1.7.0 Architektur-Blueprint" on branch `ccr-2bedd367-bz8g9g`.
Unit tests: 309 (Android, Robolectric) and 36 (bridge, Python), all passing. None of this has
been tested on a real phone yet. The checks in section 1.5 need real hardware.

Legend: ✅ done · 🟡 partly done · ⬜ open

## 1. Bugs

### 1.1 Messenger deadlock (P0) ✅
- ✅ Approve/Reject panel in the accessibility overlay above the target app (`ControlOverlay.showApproval`, `PhoneControlSession.requestApproval`). The chat card is shown too, and the first answer wins.
- ✅ The buttons only react after 600 ms. Gestures onto the panel, or while it is open, are refused.
- ✅ `restoreForeground`: if AMC is in front after the approval, the target app is brought back with `NEW_TASK` only. The action then runs only if package, label and view ID are unchanged (fingerprint check).
- ✅ Checked calls are released on reject, block and loop protection.

### 1.2 Text input ✅
- ✅ `findField` → `ElementMatcher.findInputField`. It searches descendants at any depth, then siblings, then the nearest field. It never returns a label.
- ✅ `showingHint` (API 26+, and also when text == hint). The serializer prints `hint="…" empty`.
- ✅ `relocate` for input fields: by view ID, then class + hint, then focused field, then the only field.
- ✅ `TextInputStrategy`: focus, then `SET_TEXT` with verification and cursor moved to the end. Then clipboard paste (marked sensitive, old clip restored or cleared). Then Shizuku (`input text` for ASCII, the paste key otherwise). The error message names the stage that failed.
- ✅ `SubmitResolver`: in messengers it taps the send button. Success counts only when the field is empty afterwards.
- ✅ `phone_control_service.xml`: new event types and `flagIncludeNotImportantViews`.

### 1.3 Termux bridge ✅
- ✅ Several commands per connection (one task per `execution_id`, at most 4 at once). Old daemons get a retry on the client side.
- ✅ Append happens on the server (`mode=append`). Old daemons: only "file not found" counts as empty.
- ✅ Large writes are sent in chunks (256k characters each, 64 MiB limit).
- ✅ `readFile` and `readFileBase64` check the return value of `ws.send`.
- ✅ `local_model_manager.sh` rewritten: non-interactive, uses the `llama-cpp` package, starts the server with `setsid`, uses ctx 8k/16k and passes `--jinja`.

### 1.4 Engine ✅
- ✅ E1 `PhoneThread` (HandlerThread) for node access and gesture callbacks.
- ✅ E2 capture budget of 1.2 s with `partial`, plus API 33 prefetch. ⬜ Retry with backoff when `rootInActiveWindow` is null.
- ✅ E3 gesture timeout (duration + 1.5 s) and one retry after `onCancelled`.
- ✅ E4 approval timeout (pref, 10 min by default). Notification actions; Approve requires unlocking the phone.
- ✅ E5 `FLAG_KEEP_SCREEN_ON`. A display that is off counts as transient, not as a loop.
- ✅ E6 settle only counts events from the target app (not AMC, keyboard or SystemUI) and waits for the expected event; cap 1.2 s. ⬜ Structure hash.
- ✅ E7 `ScreenLease` per run. Per-run state (snapshots) in `RunState`.
- ✅ E8 `turnSnapshot` per LLM turn.
- ✅ E9 the pill is only crossed when "pass-through" is confirmed; otherwise the gesture is refused.
- ✅ E10 timeouts: cloud 60 s idle / 6 min per step; LOCAL 600 s first token / 15 min.

### 1.5 Real hardware 🟡
- ✅ Popup windows of the same app are merged in. ⬜ Popups of other apps (permission dialog): left out on purpose because of the privacy gate.
- ✅ The pill sits below the status bar or cutout, at the side.
- ✅ Swipes start at least 32 dp from the edge.
- ✅ "Accessibility enabled, but not running" is shown in the Phone control card.
- ✅ Shizuku: sticky binder listener, and the tool list is recomputed every step. ⬜ Migration to the Shizuku UserService, binary `screencap`, Xiaomi note.
- ✅ Android 15 `onTimeout` (was already there).
- ⬜ Turning off the phantom process killer as a setup step.

## 2. Faster autonomy 🟡
- ✅ `screen_actions` (≤ 8 steps; `observe` = final/delta/none) with `ActionBatchParser` and `ActionBatchExecutor`.
  Up front, the batch is assessed as a whole: a blocked step blocks it, and steps that need confirmation are marked with ⚠ and approved once.
  Before each step it is assessed again; sensitive steps run only for the approved fingerprint.
  The batch stops on a failure, an app change, a new window, the lock screen or an unmet `expect`.
- ✅ ScreenCache: the risk check and the action share one capture.
- ✅ `ScreenDiff` (delta by element identity, not by position) and masking of older readings in the context.
- ✅ Prompt section updated.
- ⬜ `ToolCallDetected` per finished call (start during the stream), ModelRouter.
- ⬜ `ElementIdRegistry` (stable, monotonic IDs) and versioning. The delta uses the IDs of the new reading.
- ⬜ Serializer compression (merging text children, collapsing list rows, 48-character labels).

## 3. Local AI 🟡
- ✅ Backend B (Termux): bridge actions `llm_status/install/start/stop`, `model_catalog.json`, `DeviceProfiler`, `LocalModelCatalog.recommend`, Smart Setup with benchmark (decode ≥ 10 tok/s and 5/5 valid tool calls, otherwise one tier smaller), LOCAL as primary with the cloud model as fallback.
- ✅ Local tool profile (12 tools).
- ⬜ Backend A in the app (NDK module `:llm`, `LocalLlmService` in `:llm` process), manual mode (GGUF picker, parameters), `AgentPrompts.buildCompact`, download in the app with range resume (`LocalModelSetupWorker`).

## 4. Models
- 🟡 Catalog with Qwen3.5 4B Q4_K_M / Q4_0, Qwen3.5 2B and Gemma 4 E4B (experimental, never chosen automatically).
  **The URLs follow the unsloth naming pattern and were not checked (Hugging Face was not reachable from the build environment). `sha256` is empty.** The release pipeline must check the URLs and fill in the checksums. Until then, Smart Setup only checks the GGUF header and says so.
- ✅ The old Qwen2.5 / SmolLM2 entries were removed from the script and from `suggestedModels`.

### How the 40 % rule is read
Taken literally ("model + KV + 1.5 GB ≤ 40 % RAM"), the rule contradicts the RAM tiers: at 8 GB not even the 2B model would fit.
It is implemented like this instead: model + KV cache ≤ 40 % of total RAM, and the 1.5 GB reserve is checked against the currently free memory (as a warning).
With that reading the tiers come out as in the blueprint (see `LocalModelCatalogTest.tiersFollowTheRam`).
