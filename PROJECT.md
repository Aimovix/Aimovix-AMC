# Aimovix-AMC Project Specification

## Overview

Aimovix-AMC (AI Mobile Center) is an autonomous on-device AI agent architecture for Android, bridging native mobile capabilities with a Linux execution environment in Termux. It empowers multimodal LLMs (Google Gemini, OpenAI, Anthropic Claude, Groq, OpenRouter, and local GGUF models) to plan and execute tasks using mobile shell utilities, file manipulation, hardware interfaces, and Termux:API.

## Release History and Version Alignment

- **v1.2.4**: Comprehensive engine hardening, bridge keep-alive grace windows, and initial Android architecture stabilization.
- **v1.2.5**: Connection stability hardening, full English localization, Room DB SQLite synchronization, Direct Boot keystore resiliency, and expanded E2E verification suites.

---

## § Feature Inventory

### Feature 1: Termux Disconnect Process Grace
The Termux bridge daemon maintains active command executions in detached sessions (`DETACHED_SESSIONS`) during transient WebSocket disconnections. A 30-second grace period preserves running tasks across brief app switches or sleep transitions, buffering streaming outputs until reconnection.

### Feature 2: Termux Wake-Lock Lifecycle
The bridge daemon ensures the device CPU remains active during intensive tasks via `termux-wake-lock`. Wake-locks are acquired on daemon startup or command execution and released reliably via `release_wake_lock()` during graceful shutdown, signal traps (`SIGINT`, `SIGTERM`), or daemon termination.

### Feature 3: Termux Battery Polling Resilience
System telemetry utilizes low-overhead kernel sysfs queries (`/sys/class/power_supply/battery`) for non-blocking status checks. If sysfs is inaccessible, it falls back to `termux-battery-status` with exponential backoff and circuit-breaker throttling to prevent loop freezes on unresponsive APIs.

### Feature 4: Termux Pipe & Task Resource Cleanup
Asyncio subprocess transports, standard input, standard output, and standard error file descriptors are explicitly closed via `cleanup_process_resources()`. This eliminates unclosed pipe warnings, descriptor leaks, and hangs across process lifecycles.

### Feature 5: Termux Null Handling & Symlinks
Path validation in `validate_file_path()` guards against path traversal while supporting symlinked shared storage paths configured under `~/storage/`. Protocol message handling defensively normalizes empty or null working directories and execution identifiers.

### Feature 6: Termux Bridge Test Suite Expansion
A comprehensive suite of unit tests covers authentication enforcement, origin header rejection, command timeouts, isolated state handling, process group signaling, and streaming buffer limits.

### Feature 7: Android Exponential Backoff
The Android bridge client calculates reconnection delays using jittered exponential backoff (1s minimum to 30s maximum) to avoid connection storms during daemon restarts or network disruptions.

### Feature 8: Android WakeLock Acquisition
`AgentForegroundService` acquires an Android `PARTIAL_WAKE_LOCK` with reference counting during active agent processing to prevent OS-level process freezing.

### Feature 9: Android Bridge Lifecycle Decoupling
The WebSocket client connection lifecycle is decoupled from UI activity lifecycles, maintaining background execution through foreground services and WorkManager background tasks.

### Feature 10: Android Reconnect Race Guard
Thread-safe coroutine mutexes prevent concurrent reconnection attempts when rapid connection state events occur simultaneously.

### Feature 11: Android Resilient Ping Timeouts
The bridge connection maintains a lightweight WebSocket heartbeat with ping intervals and timeout thresholds configured to prevent false-positive disconnects on congested mobile networks.

### Feature 12: Android Network Callback Integration
`ConnectivityManager.NetworkCallback` monitors real-time network interface switches (Wi-Fi, cellular) to trigger instant reconnection without waiting for polling timers.

### Feature 13: Android Protocol Asymmetry Fixes
Protocol message handling aligns all client requests (`execute`, `interrupt`, `read_file`, `write_file`, `read_file_base64`, `sys_info`) with symmetric daemon responses and structured error envelopes.

### Feature 14: Android Unit Test Suite Addition
The Kotlin codebase includes Robolectric and MockWebServer unit tests validating security filters, Room database operations, agent loop state transitions, and LLM SSE stream parsing.

### Feature 15: Android Room DB Synchronization
Chat sessions, message histories, and security audit logs are persisted via Room SQLite. State updates ensure in-memory state flows synchronize without UI flickering or concurrent race conditions.

### Feature 16: Direct Boot / Keystore Robustness
Encrypted preferences leverage Android Keystore (AES-256-GCM). Graceful handling informs users to unlock their device when encrypted storage cannot be accessed, preventing credential loss or silent fallback to plaintext.

### Feature 17: Deprecated API Migrations
Android components adhere to modern Jetpack and Kotlin idioms, utilizing Kotlin `Enum.entries`, type-safe Navigation arguments, and updated Compose Material3 APIs.

### Feature 18: Documentation & Version Alignment
All documentation, error strings, UI elements, and code comments are standardized in clear, consistent English across Android and Python components, matching the latest release specifications.

### Feature 19: E2E Testing Infrastructure
A unified E2E test runner (`tests/e2e/runner.py`) provides multi-tier test suites (Features, Boundaries, Combinations, Scenarios, and Adversarial Fuzzing) using ephemeral daemon process supervision.

### Feature 20: Final Integration & Adversarial Verification
Adversarial test cases verify resilience against malformed WebSocket frames, token brute-force attempts, process group killing, payload size limits, and command injection attacks.
