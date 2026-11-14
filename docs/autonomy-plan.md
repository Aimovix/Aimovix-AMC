# AMC Autonomie-Plan (Executor-Plan)

Pfade ohne Präfix: `android/app/src/main/java/com/agent/mobile/`. `Engine` = `agent/AutonomousAgentEngine.kt`. Tests: `android/app/src/test/java/com/agent/mobile/…`, Bridge: `termux-bridge/test_*.py`.

## 0. Paritätstabelle

| # | Status | AMC heute | Fehlt für Parität |
|---|---|---|---|
| 1 Gateway | 🟡 | `channel/ChannelGatewayService.kt:85` (START_STICKY), `channel/BootReceiver.kt:13` | Läuft nur bei aktivem Telegram; Runs überleben Prozess-Tod nicht; Akku-/Phantom-Setup nur für Termux |
| 2 Channels | 🟡 | `channel/telegram/TelegramGateway.kt:112` | Nur Telegram; kein Notification-Channel (WhatsApp/Signal) |
| 3 Heartbeat | 🟡 | `service/scheduler/AgentTaskWorker.kt:48-51`, `SchedulerManager.kt:176` | Checkliste liegt in Prefs, keine `HEARTBEAT.md`, die der Agent selbst pflegt |
| 4 Cron/Trigger | 🟡 | `SchedulerManager.kt:111`, `phone/AndroidPhoneController.kt:622-633` | Runs auf 9 min/25 Schritte gedeckelt (`Engine:54-55`), Approvals werden übersprungen |
| 5 Memory | 🟡 | `agent/MemoryStore.kt:16`, `Engine:1254` (`search_history`) | Keine Tageslogs, keine Kompaktierung, keine gemeinsame Suche |
| 6 Skills | 🟡 | `agent/AgentPrompts.kt:109-115`, `Engine:703` | Kein Test-/Verbesserungszyklus, keine Nutzungsdaten |
| 7 Sub-Agents | 🟡 | `Engine:1358` | Untypisiert; Tool-Calls laufen sequenziell (`Engine:842`) |
| 8 Self-Improvement | ⬜ | Persona nur lesend (`Engine:646`) | Agent kann Brain-Dateien nicht ändern; kein Log/Revert |
| 9 Provider | 🟡 | `data/model/AgentModels.kt:48-109`, `Engine:787-811` | Ein Fallback-Versuch, kein Backoff, kein Router |
| 10 Voice | 🟡 | `ui/chat/components/VoiceInputButton.kt`, `AgentPrompts.kt:68` (TTS via Termux) | Keine Sprachnachrichten über Telegram |
| 11 Browser | 🟡 | `data/network/WebSearcher.kt`, `WebFetcher.kt`, Screen-Tools | Kein Browsing-Skill; Screen-Tools in UNATTENDED praktisch nutzlos (Approval-Skip) |
| 12 Device body | ✅ | `agent/PhoneTools.kt`, `AgentPrompts.kt:68` | – |
| 13 Proaktiv | 🟡 | `AgentRuntime.kt:55-57`, `AgentTaskWorker.kt:84-91` | Nur Android-Notification, kein Telegram; keine Rückfrage mit Resume |

## 1. Gap-Tabelle

| ID | Bereich | Problem | Evidenz | Impact |
|---|---|---|---|---|
| G1 | Approval | UNATTENDED verwirft approval-pflichtige Aktionen statt zu parken | `Engine:1054-1057`, `agent/AgentPlatform.kt:37` | H |
| G2 | Persistenz | Kein Run-Zustand; nach Prozess-Tod/Reboot bleibt der Task liegen | `Engine:228-240`, `Engine:480-488` (Session-Reuse nur REMOTE) | H |
| G3 | Provider | Fehler → ein Fallback-Versuch, dann `STOP`; kein Retry/Backoff/`Retry-After` | `Engine:787-818` | H |
| G4 | Budget | UNATTENDED endet hart bei 25 Schritten/9 min, ohne Fortsetzung | `Engine:54-55`, `Engine:680-687` | H |
| G5 | Bridge | Self-Heal nur beim Run-Start, nicht mitten im Run | `AgentTaskWorker.kt:72`, `channel/EngineChannelAgent.kt:42`, `Engine:1171` | H |
| G6 | Kontext | Alte Nachrichten werden verworfen, nicht zusammengefasst | `Engine:1558-1600` | M |
| G7 | Proaktiv | `notify_user` und Task-Ergebnisse erreichen Telegram nicht | `AgentRuntime.kt:55-57` | H |
| G8 | Setup | Akku-Ausnahme nur für `com.termux` geprüft; Phantom-Process-Killer offen | `ui/setup/SetupChecklist.kt:182`, `docs/blueprint-v1.7-status.md:51` | H |
| G9 | Wiring | Approval-Timeout-Pref hat keinen Settings-Eintrag (`saveApprovalTimeoutMinutes` ungenutzt) | `Engine:1108` | M |
| G10 | Loop | Erkennung nur über identische Beschreibungen; kein „kein Fortschritt“ | `Engine:1707-1725` | M |
| G11 | Router | ModelRouter und Per-Call-Streaming offen | `docs/blueprint-v1.7-status.md:61` | M |
| G12 | Screen | `rootInActiveWindow`-Retry, `ElementIdRegistry`, Serializer-Kompression offen | `docs/blueprint-v1.7-status.md:34,62,63` | M |
| G13 | Lokal | Modell-URLs ungeprüft, `sha256` leer; Backend A fehlt | `docs/blueprint-v1.7-status.md:68,72` | M |
| G14 | Workflow | `AgentWorkflowWorker` lehnt Approval-Jobs ab statt sie zu parken | `README.md:183` | L |

## 2. Phasen

### P0 – Pflicht für Autonomie

- **A1 · Approval-Queue (Persistenz + Benachrichtigung)** · neu `data/storage/PendingApprovalEntity`/Dao, `Engine:1045-1057`, `agent/ApprovalActions.kt`, `service/AgentNotifier.kt` · In UNATTENDED wird der Call (redigierte Argumente, Assessment, Fingerprint = Hash aus Tool + Argumenten + Zielpaket) gespeichert, der Run endet mit Status `PARKED`, Notification mit Approve/Reject. Calls mit `redact` werden nie geparkt, sondern gemeldet. · Akzeptanz: UNATTENDED-Run mit ASK-Call erzeugt genau einen Queue-Eintrag und führt nichts aus. · Test: `HeadlessAgentRunTest` + neuer `PendingApprovalDaoTest`. · dep: – · L · Opus
- **A2 · Approve-then-Resume** · `service/scheduler/AgentTaskWorker.kt` (neu `KIND_RESUME`), `SchedulerManager.kt`, `Engine:466-488` · Antwort enqueued einen Resume-Worker; `runHeadless` nimmt für UNATTENDED eine bestehende `sessionId` an und führt den genehmigten Call einmalig aus, wenn der Fingerprint passt; sonst erneute Frage. Reject/Timeout → Tool-Ergebnis „rejected“, Run läuft weiter. · Akzeptanz: nach Approve wird exakt der geparkte Call ausgeführt und der Run beendet; geänderter Fingerprint führt nicht aus. · Test: `HeadlessAgentRunTest.resumeAfterApproval`. · dep: A1 · L · Opus
- **A3 · Run-Checkpoint und Wiederaufnahme** · neu `AgentRunEntity` (sessionId, mode, title, status, step, updatedAt), `Engine:627`, `channel/BootReceiver.kt`, `MainActivity.kt:78` · Status wird je Schritt geschrieben; bei Boot/App-Start/Worker-Start werden `RUNNING`-Runs ohne lebenden Job über A2-Pfad fortgesetzt (max. 2 Wiederaufnahmen, dann Bericht). Abgebrochene Tool-Calls werden mit `recoverAbortedToolCalls` (`Engine:1905`) geschlossen. · Akzeptanz: simulierter Abbruch nach Schritt 3 → zweiter Lauf setzt in derselben Session fort. · Test: neuer `RunResumeTest`. · dep: A2 · L · Opus
- **A4 · Provider-Retry mit Backoff** · `Engine:787-818`, `data/network/LlmClient.kt:353,632,913` · Retrybare Fehler: bis zu 3 Versuche mit jitter-Backoff, `Retry-After` beachten, dann Fallback, im nächsten Schritt wieder Primary. In UNATTENDED bei endgültigem Fehler `Result.retry()` statt Abbruch. · Akzeptanz: 429, 429, 200 → Schritt gelingt ohne Fallback. · Test: `AutonomousAgentEngineTest`, `LlmClientTest` (`Retry-After`). · dep: – · M · Opus
- **A5 · Lange UNATTENDED-Runs** · `AgentTaskWorker.kt:33`, `Engine:54-55`, `Engine:680-687` · Worker ruft `setForeground` (FGS-Typ wie `AgentForegroundService`, (assumption) `dataSync` genügt); bei Schritt-/Zeitlimit wird ein Checkpoint geschrieben und eine Fortsetzung enqueued (max. 3, danach Bericht mit Stand). · Akzeptanz: Run mit 60 nötigen Schritten endet nach Fortsetzungen mit Summary. · Test: `HeadlessAgentRunTest.continuesAfterStepLimit`. · dep: A3 · M · Opus · 📱
- **A6 · Bridge-Self-Heal im Run** · `Engine:1171-1195`, `core/termux/BridgeStarter.kt:46` · Engine erhält `bridgeRecovery: suspend () -> Boolean`; Bridge-Tools rufen es bei nicht verbundenem Client auf und wiederholen einmal. · Akzeptanz: getrennte Bridge → Recovery einmal aufgerufen, Command gelingt. · Test: `AutonomousAgentEngineTest`. · dep: – · S · Sonnet
- **A7 · Proaktive Zustellung über Telegram** · `AgentRuntime.kt:55-57`, `channel/TelegramChannelManager.kt`, `AgentTaskWorker.kt:84-91`, `ui/settings/components/ChannelsCard.kt` · Neue Methode `sendToOwner` ((assumption) existiert nicht) für gepairte Chats; `notify_user`, Task-Ergebnisse und A1-Fragen (als Buttons) gehen bei aktivem Schalter zusätzlich dorthin. · Akzeptanz: UNATTENDED-Ergebnis erscheint als Telegram-Nachricht; nur an gepairte Chats. · Test: `TelegramGatewayTest`. · dep: A1 · M · Sonnet
- **A8 · Setup: Akku und Phantom-Killer** · `ui/setup/SetupChecklist.kt:182`, `ui/setup/PhoneControlChecklist.kt`, `phone/PrivilegedCommandPolicy.kt` · Schritt „AMC: Akku uneingeschränkt“ und, bei Shizuku, ein vom Nutzer ausgelöster Schritt zum Abschalten des Phantom-Process-Killers; ohne Shizuku der ADB-Befehl als Text. Der Agent selbst darf den Befehl weiterhin nicht ausführen. · Akzeptanz: `setupItems` liefert beide Schritte mit korrektem Status. · Test: `PhoneControlChecklistTest`, `PrivilegedCommandPolicyTest`. · dep: – · M · Sonnet · 📱

### P1 – Funktionale Vollständigkeit

- **B1 · Kontext-Kompaktierung** · `Engine:1558-1600` · Statt Verwerfen wird der abgeschnittene Teil einmalig per LLM zusammengefasst und als Zusammenfassung in der Session gespeichert (Cache-Präfix bleibt stabil). · Akzeptanz: Fenster über Budget enthält Summary + letzte Schritte, Tool-Paare gültig. · Test: `AutonomousAgentEngineTest`. · dep: – · M · Opus
- **B2 · Approval-Timeout in Settings** · `ui/settings/components/AutonomyCard.kt`, `data/storage/PreferenceManager.kt`, `values-de` · Slider 1–60 min, ruft `saveApprovalTimeoutMinutes`. · Akzeptanz: Wert wird gespeichert und von `Engine:1108` gelesen. · Test: `PreferenceManagerTest`. · dep: – · S · Haiku
- **B3 · `HEARTBEAT.md`** · `AgentTaskWorker.kt:48-51`, `agent/TextFileStore.kt`, `AutonomyCard.kt` · Checkliste als app-private Datei wie `SOUL.md`, vom Agent per neuem Tool `update_heartbeat` (ASK außer im Autonomous-Preset) änderbar; Pref wird einmalig migriert. Standardliste enthält geparkte Approvals und fehlgeschlagene Runs. · Akzeptanz: Migration erhält den alten Text; Heartbeat liest die Datei. · Test: neuer `HeartbeatStoreTest`. · dep: A1 · M · Sonnet
- **B4 · Memory-Stufen** · `agent/MemoryStore.kt`, `data/repository/ChatRepository.kt`, `agent/AgentTools.kt` · Tageslog `memory/yyyy-MM-dd.md` (Run-Summary am Ende jedes Runs), Logs älter als 7 Tage werden zu einer Wochenzusammenfassung kompaktiert; Tool `search_memory` sucht per Room-FTS über MEMORY, Logs und Verlauf, gewichtet nach Aktualität ((assumption) FTS statt Embeddings, da kein Server). · Akzeptanz: Treffer aus allen drei Quellen, sortiert. · Test: `RoomDaoTest`, neuer `MemoryLogTest`. · dep: – · L · Sonnet
- **B5 · Typisierte, parallele Sub-Agents** · `Engine:842`, `Engine:1358`, `agent/AgentTools.kt`, `AgentPrompts.kt` · `delegate_task` bekommt `type` (explore/plan/code/research) mit eigenem Tool-Profil und Prompt-Zusatz; mehrere `delegate_task`-Calls eines Turns laufen parallel (max. 3), Approvals bleiben serialisiert. · Akzeptanz: zwei Delegationen überlappen zeitlich; `explore` hat kein `write_file`. · Test: `HeadlessAgentRunTest`. · dep: – · L · Opus
- **B6 · Self-Improvement mit Log und Revert** · `agent/TextFileStore.kt`, `agent/AgentTools.kt`, `AutonomyCard.kt` · Tool `update_persona` (immer ASK) schreibt `SOUL.md`, legt vorher eine Version ab (letzte 10) und protokolliert im Audit-Log; Settings zeigt Verlauf mit „Wiederherstellen“. · Akzeptanz: Änderung → Version + Audit-Eintrag; Restore stellt Bytes exakt her. · Test: neuer `PersonaVersioningTest`. · dep: – · M · Sonnet
- **B7 · ModelRouter** · neu `agent/ModelRouter.kt`, `Engine:731-733`, `SettingsScreen.kt` · Reine Funktion wählt pro Schritt Primary oder Fallback: Folge-Schritte nach reinem Screen-Delta → lokal (wenn LOCAL konfiguriert), Planung/Fehlerfall → Cloud; Failover in beide Richtungen. Schalter „Smart routing“, Standard aus. · Akzeptanz: Tabellen-Test der Routing-Regeln; aus = heutiges Verhalten. · Test: neuer `ModelRouterTest`. · dep: A4 · M · Opus
- **B8 · Fortschritts-basierte Stuck-Erkennung** · `Engine:1707-1725` · Zusätzlich: N Schritte ohne neuen Screen-Hash/Plan-Fortschritt → einmal Rethink-Hinweis, dann Bericht statt stillem Stopp. · Akzeptanz: 6 wechselnde, wirkungslose Aktionen lösen Hinweis aus. · Test: `AutonomousAgentEngineTest`. · dep: – · S · Sonnet
- **B9 · Screen-Reste aus Blueprint** · `phone/AndroidPhoneController.kt`, `phone/ScreenTreeSerializer.kt`, `phone/ScreenDiff.kt` · (a) Retry mit Backoff bei `rootInActiveWindow == null`, (b) `ElementIdRegistry`, (c) Serializer-Kompression – drei PRs. · Akzeptanz: je Punkt ein Test; IDs bleiben über zwei Captures stabil. · Test: `ScreenTreeSerializerTest`, `ActionBatchTest`. · dep: – · je M · Sonnet · 📱
- **B10 · Modellkatalog verifizieren** · `termux-bridge/model_catalog.json`, `.github/workflows/android-ci.yml` · CI-Skript prüft URLs (HEAD) und füllt/prüft `sha256`. · Akzeptanz: CI schlägt bei totem Link fehl. · Test: `termux-bridge/test_model_catalog.py`. · dep: – · S · Haiku
- **B11 · `AgentWorkflowWorker` an Queue** · `service/scheduler/AgentWorkflowWorker.kt:35` · Approval-pflichtige Jobs parken über A1 statt abzulehnen. · Akzeptanz: Job landet in der Queue, läuft nach Approve. · Test: neuer `AgentWorkflowWorkerTest`. · dep: A2 · S · Sonnet

### P2 – Volles Potenzial

- **C1 · Skill-Lebenszyklus** · `agent/SkillsIndex.kt`, `AgentPrompts.kt:109-115`, `termux-bridge/setup.sh` · Front-Matter um `uses`/`last_failed` erweitert (Agent pflegt per `write_file`); Prompt verlangt nach neuem Skill einen Probelauf und nach Fehlschlag eine Korrektur; Heartbeat-Standardpunkt „Skills prüfen“. · Akzeptanz: `SkillsIndex.parse` liest neue Felder, alte Skills bleiben gültig. · Test: `SkillsIndex`-Test. · dep: B3 · M · Sonnet
- **C2 · Mitgelieferte Kombi-Skills** · `termux-bridge/setup.sh`, neu `termux-bridge/skills/*/SKILL.md` · Vier Skills: Notification-Watch (`notification_trigger` + `schedule_task` + Telegram), Recherche (`delegate_task` parallel), Browser (`open_app` + `screen_actions`), Tagesbriefing (Memory + Kalender). · Akzeptanz: Installer kopiert, Index listet sie. · Test: `termux-bridge/test_setup_skills.py`. · dep: B5, A7 · S · Haiku
- **C3 · Telegram-Sprachnachrichten** · `channel/telegram/TelegramGateway.kt`, `TelegramBotApi.kt` · Voice-Datei laden, über Bridge transkribieren ((assumption) `whisper.cpp` in Termux), Text als Turn. · Akzeptanz: Voice-Update erzeugt Turn mit Transkript. · Test: `TelegramGatewayTest`. · dep: Frage 3 · M · Sonnet · 📱
- **C4 · Notification-Channel (WhatsApp/Signal)** · neu `channel/NotificationChannelAgent.kt`, `phone/AmcNotificationListener.kt:71` · Freigegebene Kontakte einer App starten REMOTE-Turns, Antwort über `notification_reply`. Konflikt: Antworten sind `alwaysConfirm` → Sicherheit gewinnt, jede Antwort wird beim Owner bestätigt, sofern Frage 1 nichts anderes entscheidet. · Akzeptanz: nur gelistete Absender lösen Turns aus. · Test: `NotificationModelsTest`. · dep: A7, Frage 1 · L · Opus · 📱
- **C5 · Per-Call-Streaming** · `LlmClient.kt` (`ToolCallDetected`), `Engine:766` · Event je fertigem Tool-Call; Read-only-Tools starten während des Streams. · Akzeptanz: zwei Calls → zwei Events in Reihenfolge. · Test: `LlmClientTest`. · dep: – · M · Opus

### P3 – Feinschliff

- **D1 · Geräte-Testskript** · neu `docs/device-test.md` (Abschnitt E unten) · S · Haiku · 📱
- **D2 · Doku** · `PROJECT.md`, `README.md`, `docs/blueprint-v1.7-status.md` · einziger Doku-PR, nach allem anderen · S · Haiku
- **D3 · Backend A (`:llm` NDK)** · nur nach Frage 2 · L · Opus · 📱

## 3. Reihenfolge

A1 → A2 → A3 → A5; parallel dazu A4, A6, A8, B2, B10 (keine gemeinsamen Dateien). Danach A7 → B3 → B11; B1, B8, B5, B7 nacheinander (alle `Engine`); parallel B4, B6, B9. Dann C1 → C2, C5, C3, C4; zuletzt D1, D2.

## 4. Risiken und Sicherheit

- Resume darf nie mehr ausführen als genehmigt: Fingerprint-Bindung, einmalige Gültigkeit, `redact`-Calls nie parken (A1/A2).
- Telegram-Zustellung leitet Inhalte über fremde Server: Schalter, Standard aus, nur gepairte Chats (A7).
- Parallele Sub-Agents teilen eine Bridge (max. 4 Commands) und einen Bildschirm: Screen-Tools bleiben für Sub-Agents gesperrt (B5).
- Self-Edit von Persona/Heartbeat ist ein Prompt-Injection-Ziel: immer ASK bzw. versioniert und rückholbar (B3/B6).
- 100 % Autonomie vs. Sicherheit: SMS, Anrufe, Zahlungen, Löschen und Messenger-Antworten bleiben gate-pflichtig; Autonomie entsteht hier nur durch Parken + Resume.

## 5. Fragen an den Owner

1. C4: Darf der Owner pro Kontakt eine dauerhafte Antwort-Freigabe erteilen, oder bleibt jede Antwort einzeln zu bestätigen (Standard im Plan)?
2. D3: Soll Backend A (In-App-NDK) noch gebaut werden, oder bleibt Termux-`llama-server` die einzige lokale Variante?
3. C3: STT lokal in Termux (`whisper.cpp`, langsamer) oder über das Audio-Input des Cloud-Providers?

## E. Geräte-Test 📱 (Bestanden-Kriterien)

1. **Reboot:** Telegram aktiv, Neustart, ohne App zu öffnen Nachricht senden → Antwort < 2 min.
2. **Bridge tot:** `amc stop`, dann geplanter Task mit Shell-Befehl → Bridge startet selbst, Task endet erfolgreich.
3. **Park + Resume:** geplanter Task „lösche Datei X“ → Notification/Telegram-Frage, Approve nach 5 min → Datei weg, Abschlussmeldung.
4. **Prozess-Tod:** langen Task starten, AMC per „Stopp erzwingen“ beenden und öffnen → Task läuft in derselben Session weiter.
5. **Doze:** Bildschirm aus, 60 min, Heartbeat 15 min → mindestens 3 Läufe im Audit-Log.
6. **Provider-Fehler:** falscher Primary-Key mit Fallback → Task endet über Fallback, ein Hinweis im Chat.
7. **Langer Run:** Task mit > 40 Schritten unattended → Summary nach Fortsetzungen, kein stiller Abbruch.
8. **Trigger-Kette:** `notification_trigger` auf Test-App → Sub-Agent-Recherche → Ergebnis in Telegram.
