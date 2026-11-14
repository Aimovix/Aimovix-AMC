# AMC Local-AI-Plan (Executor-Plan)

Pfade ohne Präfix: `android/app/src/main/java/com/agent/mobile/`. Tests: `android/app/src/test/java/com/agent/mobile/…`, Bridge: `termux-bridge/test_*.py`.

## 1. Recherche (Stand November 2026)

Es gibt keinen einheitlichen Tool-Calling-Benchmark für 1–5 B; die Quellen sind überwiegend Blog-Übersichten. Deshalb bleibt der eigene Benchmark auf dem Gerät (`local/LocalModelSetup.kt:118`) die Entscheidungsinstanz.

### R1 Modelle

| Modell | Tool-Calling | Lizenz | GGUF | Urteil |
|---|---|---|---|---|
| Qwen3.5 2B / 4B | nativ, Template-Fixes von unsloth | Apache 2.0 (laut Drittquellen) | unsloth, bartowski | **Standard** |
| Gemma 4 E2B / E4B | nativ | Apache 2.0 (laut Mirrors) | unsloth (auch QAT) | experimentell; Q4_K_M ≈ 5,0–5,4 GB, nicht 3 GB |
| Phi-4-mini 3.8B | eigenes Format | MIT | ja | Reserve, nicht im Katalog |
| Llama 3.2 3B | über Framework | Llama-Lizenz | ja | abgelehnt (Lizenz, älter) |
| SmolLM2/3 | schwach in Multi-Tool-Schleifen | Apache 2.0 | ja | abgelehnt |
| MiniCPM5 1B/2B, BotPsy 0.8B (neu) | beworben, ungeprüft | Apache 2.0 (BotPsy) | ja / angekündigt | beobachten, erst nach Gerätebenchmark |

Quellen: https://www.digitalapplied.com/blog/small-language-models-on-device-agents-2026-guide · https://www.promptquorum.com/power-local-llm/best-local-models-tool-calling-2026 · https://unsloth.ai/docs/models/qwen3.5 · https://huggingface.co/unsloth/gemma-4-e4b-it-qat-GGUF · https://huggingface.co/openbmb/MiniCPM5-2B-GGUF · https://explainx.ai/blog/botpsy-0-8b-on-device-agent-qvac-apache-tool-use-2026

### R2 Runtime

| Runtime | Tempo | GPU/NPU | Tool-Calling | Integration | ohne Play / Sideload |
|---|---|---|---|---|---|
| llama.cpp in Termux | CPU 4–14 tok/s (1–4 B, Flaggschiff) | OpenCL-Adreno: Prefill bis 24×, Decode uneinheitlich; für Q4_0 optimiert | `--jinja`, OpenAI-API | vorhanden | ja |
| llama.cpp in-app (NDK) | gleich | gleich | selbst zu bauen | groß (NDK, CI, APK) | ja |
| MLC LLM | 10,8 tok/s (S24 Ultra, Studie) | Vulkan | eingeschränkt | Modell pro Gerät kompilieren | ja |
| LiteRT-LM | 28–58 tok/s (Herstellerangaben) | GPU/NPU | Constrained Decoding, Gemma | eigenes Modellformat | nicht belegt |
| ONNX Runtime GenAI (QNN) | NPU am effizientesten | Hexagon | wenige Modelle | SoC-spezifische Binaries | ja |
| AICore / Gemini Nano | – | NPU | begrenzt | klein | **nein** |

Quellen: https://arxiv.org/html/2603.23640v2 · https://github.com/ggml-org/llama.cpp/discussions/23736 · https://github.com/ggml-org/llama.cpp/issues/16217 · https://developers.google.com/edge/litert-lm/overview · https://iotdigitaltwinplm.com/on-device-llm-runtimes-llama-cpp-vs-mlc-vs-onnx-2026/

### R3 Dateien

| Datei | URL | Größe | SHA-256 |
|---|---|---|---|
| Qwen3.5-4B-Q4_K_M.gguf | https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf | 2,74 GB (bestätigt) | LFS-Hash auf https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/blob/main/Qwen3.5-4B-Q4_K_M.gguf |
| Qwen3.5-4B-Q4_0.gguf | gleicher Repo-Pfad | 2,58 GB (bestätigt) | wie oben |
| Qwen3.5-2B-Q4_K_M.gguf | `unsloth/Qwen3.5-2B-GGUF` (Repo in unsloth-Doku genannt; Dateiname (assumption)) | (assumption) 1,5 GB | wie oben |
| Gemma 4 E4B | `unsloth/gemma-4-e4b-it-qat-GGUF`, Datei `UD-Q4_K_XL` (genauer Name (assumption)) | ≈ 5,3 GB | wie oben |

Hashes wurden nicht abgerufen; Task M1 holt sie maschinell über die Hugging-Face-API (`/api/models/<repo>/tree/main`, Feld `lfs.oid` (assumption)).

## 2. Entscheidungen

- **D1 Runtime: Termux bleibt die einzige Runtime.** Termux ist für die Shell ohnehin Pflicht, `llama-server` liefert Tool-Calls und OpenAI-API ohne nativen Code. Abgelehnt: in-app NDK (großer Aufwand, gleiche Geschwindigkeit; nur nach Frage 1) · MLC (Kompilat pro Modell) · LiteRT-LM (eigenes Format, Play-Unabhängigkeit unbelegt) · ONNX/QNN (SoC-Binaries, kaum Tool-Modelle) · AICore (Play-Pflicht).
- **D2 Modelle:** ≤ 6 GB: Qwen3.5 2B Q4_K_M, ctx 8k, nur Screen-Schritte · 8 GB: Qwen3.5 4B Q4_0, ctx 8k · 12 GB: Qwen3.5 4B Q4_K_M, ctx 16k · 16+ GB: dasselbe, ctx 32k. Ein Modell gleichzeitig; „schnell“ = 2B, „denkend“ = Cloud-Fallback. Abgelehnt: zwei lokale Modelle parallel (RAM, Hitze) · Gemma als Standard (doppelt so groß, ungetestet).
- **D3 Download in Termux, losgelöst vom Bridge-Aufruf.** `curl -C -` existiert, die Datei liegt dort, wo `llama-server` sie braucht (`~/.amc/models/`). Die App steuert und zeigt an (WorkManager + Foreground-Notification). Abgelehnt: Download in der App (Termux kann app-private Dateien nicht lesen; Kopie verdoppelt den Speicherbedarf).
- **D4 Fortschritt:** Phasen `CHECK → DOWNLOAD → VERIFY → START → BENCHMARK → DONE`; Balken zeigt in DOWNLOAD Bytes (Prozent, MB, MB/s, Restzeit), sonst unbestimmt mit Phasentext. Zustand liegt in `PreferenceManager` (kein Room-Schema-Wechsel) und in der Statusdatei der Bridge; die Karte liest beides beim Öffnen. Abgelehnt: Room-Tabelle (Migration ohne Mehrwert).
- **D5 Verifikation:** CI-Skript füllt und prüft URL, Größe, SHA-256; Smart Setup bietet nur verifizierte Einträge an, der manuelle Modus unverifizierte nach Bestätigung (nur GGUF-Header-Prüfung). Abgelehnt: unverifiziert stillschweigend anbieten (heute `local/LocalModelCatalog.kt:102`).
- **D6 Agent:** `AgentPrompts.buildCompact` für LOCAL, `AgentTools.LOCAL_PROFILE` bleibt, ctx nach D2; Cloud übernimmt bei Timeout, Serverfehler oder zwei ungültigen Tool-Calls in Folge für den Rest des Runs. Abgelehnt: ModelRouter jetzt (eine Eskalationsregel bringt den Großteil; Router bleibt `docs/autonomy-plan.md` B7).
- **D7 Akku/Hitze:** Modell wird bei Bedarf gestartet, nach 10 min Leerlauf entladen, bei Thermal-Status ≥ SEVERE oder Akku < 15 % gestoppt (Cloud übernimmt); Optionen „nur beim Laden“ und „Download nur im WLAN“ (Standard an). Abgelehnt: Server dauerhaft laufen lassen.

## 3. Zielablauf

1. Tippen auf „Set up local AI“. Die Karte zeigt Modell, Größe und freien Speicher; reicht er nicht, erscheint „Free up storage“.
2. Ein Worker startet mit Foreground-Notification; ist die Bridge aus, startet `BridgeStarter` sie.
3. `CHECK`: Gerät, Katalog, Speicher. `DOWNLOAD`: Termux lädt im Hintergrund, die App fragt alle 2 s den Status ab.
4. `VERIFY` (SHA-256), `START` (`llama-server`), `BENCHMARK`; bei zu langsam automatisch die kleinere Stufe.
5. `DONE`: LOCAL ist primär, das bisherige Cloud-Modell Fallback; Notification „Local AI active“.
6. App verlassen: Worker und Download laufen weiter; die Karte stellt Balken und Phase aus dem gespeicherten Zustand wieder her.
7. Netz weg: `curl` wiederholt, danach wartet der Worker auf Netz und setzt bei den vorhandenen Bytes fort.
8. Prozess-Tod oder Neustart: WorkManager bzw. `BootReceiver` starten den Worker neu; der Download setzt aus der `.part`-Datei fort.
9. Abbrechen: Download stoppt; `.part` bleibt für „Fortsetzen“ oder wird mit „Löschen“ entfernt.

## 4. Tasks

### P0 – Ein-Tap-Setup mit Balken, das die App überlebt

- **L1 · Losgelöster Install mit Status** · `termux-bridge/local_model_manager.sh:135-162`, `termux-bridge/bridge_daemon.py:316,660-691,704` · `install` startet den Download per `setsid` und kehrt sofort zurück; eine Statusdatei in `~/.amc/run/` hält Phase, Bytes, Gesamtgröße, Tempo, Fehlercode. `status` gibt sie als `status.install_*` aus; neue Aktionen `llm_cancel` und `llm_delete`. Ein zweiter `install` hängt sich an den laufenden an. · Akzeptanz: `install` antwortet < 5 s; nach Kill des Downloads setzt ein neuer Aufruf bei der `.part`-Größe fort; SHA-Fehler löscht `.part` und meldet `checksum`. · Test: neu `termux-bridge/test_local_model_manager.py` (lokaler HTTP-Server mit Range), `test_bridge_daemon.py` für die neuen Aktionen. · dep: – · M · Sonnet
- **L2 · Fortsetzbare Setup-Zustandsmaschine** · neu `local/LocalSetupState.kt`, `local/LocalModelSetup.kt:79-115`, `data/storage/PreferenceManager.kt`, `data/network/TermuxBridgeClient.kt:1028` · `run` wird in Phasen zerlegt, die den Zustand speichern und `Progress(phase, bytes, total, bytesPerSecond, etaSeconds)` melden; DOWNLOAD pollt `llm_status`. Fehler werden zu Codes mit genau einer Fix-Aktion (`BRIDGE`, `NETWORK`, `STORAGE`, `CHECKSUM`, `TOO_SLOW`, `START`). · Akzeptanz: Start mit gespeichertem Zustand DOWNLOAD ruft kein zweites `CHECK` auf und setzt fort; ETA-Berechnung stimmt für feste Eingaben. · Test: neu `local/LocalModelSetupTest` (Fake-Bridge). · dep: L1 · M · Sonnet
- **L3 · `LocalModelSetupWorker`** · neu `service/scheduler/LocalModelSetupWorker.kt`, `service/scheduler/SchedulerManager.kt`, `service/AgentNotifier.kt`, `core/termux/BridgeStarter.kt:46` · Unique Work `amc_local_setup`, `setForeground` (`dataSync`), Notification mit Balken und „Cancel“, Constraint CONNECTED bzw. UNMETERED. Bridge aus → `ensureConnected` statt Fehlermeldung (`LocalModelSetup.kt:82`); Netz-/Bridge-Verlust → `Result.retry()`. Bei Erfolg setzt der Worker `localConfig` (bisher in der Karte, `ui/settings/components/LocalModelCard.kt:62-66`). · Akzeptanz: Bridge offline → `ensureConnected` aufgerufen; IOException → retry; Erfolg → Config gespeichert; Cancel ruft `llm_cancel`. · Test: neu `LocalModelSetupWorkerTest` (`TestListenableWorkerBuilder`). · dep: L2 · M · Opus · 📱
- **L4 · Karte mit Balken** · `ui/settings/components/LocalModelCard.kt:27-86`, `res/values/strings.xml`, `values-de` · Button „Set up local AI“, `LinearProgressIndicator`, Phase, MB/Tempo/Restzeit, Cancel, Fehler mit einem Fix-Button; Zustand aus WorkManager-Flow und gespeichertem Zustand, kein `rememberCoroutineScope` mehr. Speicherprüfung vor dem Start sichtbar. · Akzeptanz: Zustand DOWNLOAD 40 % zeigt Balken 0,4 und Cancel; `STORAGE` zeigt „Free up storage“; Neuaufbau der Karte zeigt denselben Zustand. · Test: neu `LocalModelCardTest` (Robolectric/Compose). · dep: L3 · M · Sonnet
- **L5 · Wiederaufnahme nach Neustart** · `channel/BootReceiver.kt:13`, `MainActivity.kt:78` · Unfertiger Zustand → Worker mit Policy KEEP erneut einreihen. · Akzeptanz: Zustand DOWNLOAD + Boot-Intent reiht genau einen Worker ein; DONE reiht keinen ein. · Test: neu `BootReceiverTest`. · dep: L3 · S · Sonnet · 📱

### P1 – Beste Modelle und Verifikation

- **M1 · Katalog prüfen und füllen** · neu `scripts/verify_model_catalog.py` ((assumption) Ordner `scripts/` neu), beide `model_catalog.json`, `.github/workflows/android-ci.yml` · Skript holt Größe und SHA-256, schreibt sie in beide Kataloge und schlägt bei 404, Abweichung oder ungleichen Kopien fehl; korrigiert Gemma (≈ 5,3 GB, `min_ram_mb` 16000). Ersetzt B10 aus `docs/autonomy-plan.md`. · Akzeptanz: CI rot bei leerem `sha256` eines nicht-experimentellen Eintrags. · Test: neu `termux-bridge/test_model_catalog.py` (offline: Schema, https, Host `huggingface.co`, Kopien identisch). · dep: – · S · Haiku
- **M2 · Stufen und unverifizierte Einträge** · `local/LocalModelCatalog.kt:84-110` · `recommend` überspringt unverifizierte Einträge, Stufen nach D2 (16+ GB: ctx 32k), Threads höchstens 6. · Akzeptanz: Katalog nur mit unverifizierten Einträgen → `NotRecommended` mit klarem Grund. · Test: `LocalModelCatalogTest`. · dep: M1 · S · Sonnet
- **M3 · Manueller Modus** · `LocalModelCard.kt`, `LocalSetupState.kt` · Aufklappbar „Advanced“: installierte Modelle (`status.models`), wechseln, löschen (`llm_delete`), Katalogmodell wählen; unverifiziert nur nach Bestätigungsdialog. · Akzeptanz: Löschen des aktiven Modells stellt das Cloud-Modell als primär wieder her. · Test: `LocalModelCardTest`. · dep: L4, M2 · M · Sonnet

### P2 – Agent-Integration und Tempo

- **A1 · `AgentPrompts.buildCompact`** · `agent/AgentPrompts.kt:145`, Aufrufstelle `AgentPrompts.build` in `runAgentLoop` (`agent/AutonomousAgentEngine.kt`) · Kompakter Prompt für LOCAL: Sicherheitsregeln, Modus, nur Tools aus `LOCAL_PROFILE`, Memory gekürzt. · Akzeptanz: ≤ 5000 Zeichen bei leerem Memory; enthält die Approval-Regel; nennt kein ausgeschlossenes Tool. · Test: neu `AgentPromptsTest`. · dep: – · M · Sonnet
- **A2 · Eskalation lokal → Cloud** · `runSingleStep` in `AutonomousAgentEngine.kt`, `data/network/LlmClient.kt:48` · Bei LOCAL als Primary: Timeout, Fehler oder zwei ungültige/unbekannte Tool-Calls in Folge → Rest des Runs über den Fallback, ein Hinweis im Chat. First-Token-Timeout 120 s, wenn ein Fallback existiert. · Akzeptanz: zwei kaputte Tool-Calls → dritter Request geht an den Fallback; ohne Fallback unverändert. · Test: `ProviderRetryTest`. · dep: – · M · Opus
- **A3 · Lebenszyklus** · neu `local/LocalModelLifecycle.kt`, `LocalModelCard.kt` · Start bei Bedarf vor dem ersten LOCAL-Request, Stopp nach 10 min Leerlauf, bei Thermal ≥ SEVERE oder Akku < 15 %; Schalter „only while charging“. · Akzeptanz: Fake-Uhr: 10 min ohne Request → ein `llm_stop`; nächster Request → `llm_start` vor dem Aufruf. · Test: neu `LocalModelLifecycleTest`. · dep: L2 · M · Opus
- **A4 · Benchmark mit Prefill** · `local/LocalModelSetup.kt:118-160` · Zusätzlich Prefill-Tempo mit einem Screen-großen Prompt; Kriterium erste Antwort ≤ 20 s ((assumption) Schwelle). · Akzeptanz: `Benchmark.passed` berücksichtigt Prefill. · Test: `LocalModelSetupTest` (MockWebServer). · dep: L2 · S · Sonnet
- **A5 · OpenCL-Adreno-Versuch** · `local_model_manager.sh:180-200` · `start --gpu auto` probiert auf Snapdragon das OpenCL-Backend ((assumption) Termux-Paket vorhanden), f16-KV, Q4_0; der Benchmark behält die schnellere Variante. · Akzeptanz: ohne OpenCL fällt `start` ohne Fehler auf CPU zurück. · Test: `test_local_model_manager.py`. · dep: A4 · M · Opus · 📱

### P3 – Feinschliff

- **D1 · Gerätetest** · neu `docs/local-ai-device-test.md`: Setup auf 8- und 12-GB-Gerät, Flugmodus bei 50 %, „Stopp erzwingen“ bei 50 %, Neustart bei 50 %, Abbrechen und Fortsetzen, voller Speicher. · S · Haiku · 📱
- **D2 · Doku** · `PROJECT.md`, `README.md`, `docs/blueprint-v1.7-status.md` · einziger Doku-PR. · S · Haiku

## 5. Reihenfolge

L1 → L2 → L3 → L4 → L5; parallel dazu M1, A1, A2. Danach M2 → M3 und A3, A4 → A5 (M3 und A3 teilen `LocalModelCard.kt`, also nacheinander); zuletzt D1, D2.

## 6. Risiken

- Speicher: 2,7 GB plus 20 % Reserve; die Prüfung liest AMCs Partition, Termux liegt auf derselben (`LocalModelSetup.kt:33`).
- Hitze/Akku: Dauerlast drosselt das Tempo; Benchmarkwerte gelten nur kalt, deshalb A3.
- Lizenzen: Apache 2.0 für Qwen3.5 und Gemma 4 stammt aus Drittquellen; M1 prüft das Lizenzfeld der Repos.
- Unverifizierte Downloads: bis M1 fertig ist, bietet Smart Setup nichts an (M2); L1–L5 sind trotzdem mit Testdatei prüfbar.
- Termux/Android: Phantom-Process-Killer kann `curl` oder `llama-server` beenden (Setup-Schritt A8 in `docs/autonomy-plan.md`); Foreground-Typ `dataSync` hat ab Android 15 ein Zeitlimit von 6 h.

## 7. Fragen an den Owner

1. Soll Backend A (in-app NDK) später noch kommen? Der Plan lässt es weg; `docs/autonomy-plan.md` D3 würde dann gestrichen.
2. Darf die CI Prüfsummen automatisch per Commit in die Kataloge schreiben, oder nur prüfen und ein Mensch trägt sie ein (blockiert M1)?
3. Telefone mit ≤ 6 GB: 2B-Modell „nur für Screen-Schritte“ anbieten (Plan) oder gar keine lokale KI?
