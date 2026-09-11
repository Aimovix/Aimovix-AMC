# 🤖 AMC – AI Mobile Center (by Aimovix)

**AMC (AI Mobile Center)** ist eine native Android-Anwendung (Kotlin + Jetpack Compose), die dein Smartphone in einen autonomen mobilen KI-Agenten verwandelt. Der Agent steuert eine lokale Termux-Linux-Umgebung und erhält über `termux-api` sowie Shell-Befehle tiefgreifenden System- und Hardware-Zugriff (SMS, Kamera, GPS, Benachrichtigungen, Sensoren, Dateisystem, Python-Skripte).

---

## 🚀 Schnellstart

### 1. App auf dem Smartphone installieren
- **Download:** Lade die aktuelle Version direkt über **[GitHub Releases](https://github.com/Aimovix/Aimovix-AMC/releases)** herunter (APK unter *Assets*).
- **Selbst bauen:** Alternativ lokal mit `./gradlew assembleRelease` kompilieren.

### 2. Termux & Termux:API installieren
Installiere beide Apps über **F-Droid** (die Version aus dem Google Play Store ist veraltet und inkompatibel):
1. **[Termux auf F-Droid](https://f-droid.org/packages/com.termux/)**
2. **[Termux:API auf F-Droid](https://f-droid.org/packages/com.termux.api/)**

### 3. Ein-Klick-Setup in Termux
Öffne Termux auf deinem Smartphone und führe folgenden Befehl aus (oder nutze den Kopieren-Button im Setup-Tab der AMC-App):

```bash
curl -sL https://raw.githubusercontent.com/Aimovix/Aimovix-AMC/main/termux-bridge/setup.sh | bash
```

Das Skript richtet die Umgebung automatisch als echten, robusten Hintergrunddienst ein:
- **CLI-Tool `amc`:** Installiert den Service-Manager direkt nach `$PREFIX/bin/amc`.
- **Hintergrund-Daemon (`nohup` + `disown` + `setsid`):** Der Prozess läuft vollständig entkoppelt von der interaktiven Shell und ignoriert `SIGHUP` (läuft weiter, wenn Termux geschlossen oder minimiert wird).
- **Vordergrund-Benachrichtigung:** Startet eine dauerhafte Android-Benachrichtigung (`termux-notification --ongoing`), die Android signalisiert, dass der Prozess aktiv ist.
- **CPU-Wake-Lock:** Aktiviert `termux-wake-lock`, um Tiefschlaf des Prozessors zu unterbinden.
- **Auto-Start:** Richtet automatischen Start für Termux:Boot (`~/.termux/boot/`) und die Shell (`~/.bashrc`) ein.
- **Auto-Reconnect in AMC:** Die Android-App verbindet sich beim Öffnen (`onResume`) und im Hintergrund automatisch im Sekundentakt neu.

#### Wichtiger Schritt für Android-Geräte (Akku-Optimierung)
Damit Android Termux beim Wechseln der Apps nicht pausiert:
1. Öffne die **Android-Einstellungen** deines Smartphones.
2. Gehe zu **Apps -> Termux -> Akku / Akkunutzung**.
3. Wähle **„Nicht optimiert“** bzw. **„Uneingeschränkt“** (*Unrestricted*).

#### Termux Service-Befehle:
```bash
amc status     # Prüft Status, PID, Port 8765, Akku & Token
amc logs       # Zeigt Live-Logs der Bridge
amc restart    # Startet den Hintergrunddienst neu
amc stop       # Beendet den Dienst
```


---

## 🧠 Unterstützte KI-Modelle & Dynamische Modellauswahl

Die App bietet im Tab **Einstellungen** sowie direkt in der Chat-Leiste eine dynamische Modellauswahl:
- **Empfohlene Modelle:** Schnellwahl per Klick über horizontale Chips für jeden Provider.
- **Freie Texteingabe:** Beliebige benutzerdefinierte Modellnamen können manuell eingetippt und gespeichert werden (z. B. Fine-tunes, Vorschau-Versionen oder OpenRouter-Slugs).
- **Direkter Modell-Wechsel im Chat:** Über die Modell-Pille in der oberen App-Leiste kann das Modell oder der Provider jederzeit im laufenden Betrieb ohne Tab-Wechsel angepasst werden.

| Provider | Modell-Beispiele (Vorschläge & Freitext) | Beschreibung |
|---|---|---|
| **Google Gemini** | `gemini-2.0-flash`, `gemini-1.5-flash`, `gemini-1.5-pro` | Natives Function Calling, hohe Geschwindigkeit |
| **OpenAI** | `gpt-4o`, `gpt-4o-mini`, `o3-mini`, `o1` | Standard-Tools & Function Calling |
| **Anthropic Claude** | `claude-3-7-sonnet`, `claude-3-5-sonnet-20241022`, `claude-3-5-haiku` | ReAct-basierte Werkzeugaufrufe |
| **Groq** | `llama-3.3-70b-versatile`, `deepseek-r1-distill-llama-70b` | Extrem schnelle Inferenz |
| **OpenRouter** | `anthropic/claude-3.5-sonnet`, `deepseek/deepseek-r1` | Zugriff auf Hunderte offene & proprietäre Modelle |
| **Lokaler llama-server** | `qwen2.5-3b-instruct`, `llama-3.2-3b-instruct` | 100% offline auf dem Smartphone via `local_model_manager.sh` |


---

## 🛡️ Sicherheitsarchitektur

AMC verfügt über ein mehrstufiges Sicherheitskonzept zum Schutz des Smartphones und der privaten Daten:

### 1. 3-Tier Command Security Filter (`CommandSecurityFilter.kt`)
Alle vom Modell generierten Befehle durchlaufen eine statische Analyse inklusive Normalisierung (Schutz gegen Token-Splitting und Quotes wie `r'm'`):

- 🛑 **BLOCKED (Ausnahmslos blockiert):**
  Destruktive Systembefehle wie `rm -rf /`, `rm -rf ~`, `mkfs`, `dd if=/dev/zero`, Fork-Bombs und unkontrollierte Download-Pipes (`curl ... | bash`). Können weder im Autopilot noch manuell freigegeben werden.
- 🔴 **HIGH (Bestätigungspflichtig):**
  Aktionen mit Hardware- oder Privatsphärezugriff (SMS senden, Anrufe starten, Fotos aufnehmen, Kontakte auslesen, System-Reboot, Dateilöschungen) sowie potenzielle Verschleierungsmethoden (Base64-Decoding in Pipes, `eval`, `exec`, Inline-Interpreter wie `python -c`, `node -e`). **Erfordert immer die Freigabe durch den Nutzer, auch im Autopilot-Modus.**
- 🟡 **MEDIUM:**
  Dateisystem-Änderungen (`mkdir`, `touch`, `cp`), reguläre Skriptdateien (`python script.py`), Downloads und Paketinstallationen.
- 🟢 **LOW:**
  Reine Lese- und Diagnosebefehle (`termux-battery-status`, `ls`, `pwd`, `whoami`).

### 2. Schutz vor Indirect Prompt Injection
- **System-Guardrails:** Fremddaten (z. B. empfangene SMS, Webseiteninhalte via `curl`, Logs) dürfen laut System-Prompt niemals Systemanweisungen oder Verhaltensregeln überschreiben.
- **Datenkapselung:** Befehlsausgaben werden vor der Übergabe an das LLM strikt in Begrenzungsmarkern (`[UNTRUSTED_OUTPUT_START] ... [UNTRUSTED_OUTPUT_END]`) isoliert.

### 3. Netzwerk-Restriktion (`network_security_config.xml`)
Klartext-Verbindungen (HTTP/WS) sind app-weit ausschließlich für die lokalen Loopback-Schnittstellen (`127.0.0.1`, `localhost`, `10.0.2.2`) freigegeben. Sämtliche Kommunikation mit externen Cloud-APIs erzwingt verschlüsseltes HTTPS.

### 4. Hardware-gestützte Keystore-Verschlüsselung (`PreferenceManager.kt`)
API-Keys und Bridge-Tokens werden mit `EncryptedSharedPreferences` über den Android Keystore (`AES256_GCM`) verschlüsselt abgelegt.

---

## ⚡ Bedienung & Autonomie

- **Autopilot-Modus:** Der Agent führt Schritte eigenständig aus, wertet Ausgaben und Fehlermeldungen aus und korrigiert sich selbst (HIGH-Risk-Aktionen pausieren dennoch für eine Bestätigung).
- **Schritt-für-Schritt-Freigabe:** Jeder Befehl muss vor der Ausführung bestätigt werden (`Ausführen` oder `Ablehnen`).
- **Live-Terminal-Streaming:** stdout/stderr-Ausgaben werden in Echtzeit in einer aufklappbaren Terminal-Box direkt im Chat gestreamt.
- **Quick-Action-Toolbar:** Vordefinierte Aktionen für Akku-Status, WLAN-Informationen, Kamera-Foto, Zwischenablage, Systembenachrichtigungen und Text-to-Speech (TTS).
- **Not-Aus-Button:** Ein schwebender roter Not-Aus-Button bricht die Ausführung und laufende Termux-Hintergrundprozesse via `SIGINT`/`SIGTERM` sofort ab.

---

## 🌟 Enterprise Mega-Upgrade & Architektur

AMC wurde auf Enterprise-Niveau gehoben und verfügt über eine modulare, resiliente und vollständig abgesicherte Architektur:

### 1. Multi-Session-Persistenz & Chat-Verwaltung (Room Database)
- **Room SQLite Engine (`AppDatabase.kt`):** Vollständige persistente Speicherung aller Chats, Nachrichten und Terminal-Ausgaben (`ChatSession`, `ChatMessageEntity`, `CommandAuditEntity`) mit Fremdschlüsselkaskadierung (`CASCADE`).
- **Multi-Session-Drawer:** Schnelles Umschalten zwischen parallelen Chat-Sessions, Erstellen neuer Sessions und Löschen alter Historien.
- **Automatische Titelgenerierung:** KI-basierte und heuristische Generierung prägnanter Titel aus der ersten Nutzeranweisung.
- **Volltextsuche:** Durchsucht alle historischen Chat-Nachrichten sowie ausgeführte Terminal-Ausgaben in Echtzeit.
- **Export (Markdown & JSON):** 1-Klick-Export vollständiger Sessions inklusive aller Tool-Calls, Token-Metriken und Terminal-Logs in den Gerätespeicher (`Documents/`).

### 2. Multimodale Vision- & Artefakt-Pipeline
- **Multi-Provider Vision (`LlmClient.kt`):** Unterstützt Bild-Inputs über alle führenden Vision-APIs:
  - Google Gemini: Natives `inlineData` Base64-Streaming
  - OpenAI / OpenRouter: Dynamische `image_url` data-URIs
  - Anthropic Claude: Base64 Source Objects (`image/jpeg`, `image/png`, `image/webp`)
- **Chat-Integration:** Fotos können direkt über die Kamera aufgenommen oder aus der Galerie an jede Chat-Nachricht angehängt werden.
- **Autonome Termux-Vision-Loop:** Wenn der Agent `termux-camera-photo` aufruft, liest der Daemon das Bild automatisch im Base64-Format aus dem Termux-Dateisystem (`read_file_base64`) und injiziert es als visuelle Beobachtung in den Chat.
- **In-App Artefakt-Viewer (`ArtifactViewerDialog.kt`):** Rendert erzeugte Dateien, Skripte, Code-Dateien und HTML-Reports in einem interaktiven Modal mit Syntax-Highlighting, Copy-Action und 1-Klick-Ausführung in Termux.

### 3. SSE Token-Streaming & Provider-Resilienz
- **Server-Sent Events (SSE):** Flüssiges Word-by-Word Streaming über Kotlin Coroutines Flow für OpenAI, Groq, OpenRouter, Claude (`/messages?stream=true`) und Gemini (`streamGenerateContent?alt=sse`).
- **Automatisches Sekundär-Provider-Fallback:** Bei Rate-Limits (HTTP 429), Timeouts oder Serverfehlern (HTTP 5xx) schaltet die Engine nahtlos und unterbrechungsfrei auf den konfigurierten Fallback-Provider um (z. B. Primär Gemini Flash -> Fallback Groq Llama 3.3).
- **Token- & Kosten-Tracking:** Protokolliert akkumulierte Prompt- und Completion-Tokens sowie geschätzte USD-Kosten pro Nachricht und Sitzung.

### 4. Hybrid-Scheduler & Hintergrund-Automation
- **Android WorkManager Integration (`AgentWorkflowWorker.kt`, `SchedulerManager.kt`):** Periodische und einmalige Hintergrund-Automationen mit Hardware-Constraints (z. B. nur bei WLAN, Akku nicht schwach, Ladezustand).
- **Termux Crontab-Sync:** Synchronisiert geplante Aufgaben direkt mit dem Linux-Cron (`crontab -l`, `crontab -`) in Termux über den Bridge-Daemon.
- **Interaktive Service-Benachrichtigungen:** Der Vordergrunddienst (`AgentForegroundService.kt`) aktualisiert Benachrichtigungen mit Live-Status und Stopp-Action.

### 5. Security-Cockpit & Guardrails
- **Sicherheits-Cockpit (`SecurityCockpitCard.kt`):**
  - **Benutzerdefinierte Whitelist-Regex:** Freigabe spezifischer Befehle ohne Bestätigungsabfrage.
  - **Benutzerdefinierte Blacklist-Regex:** Sofortige Blockierung individueller Befehlsmuster.
  - **Strikter Modus (Strict Mode):** Erzwingt auch im Autopilot-Modus Bestätigungen für mittlere Risiken.
- **Runaway & Cyclic Loop Detection:** Verhindert Endlosschleifen durch automatischen Abbruch bei:
  - Wiederholter Ausführung desselben Befehls (>= 3 Mal identisch)
  - Ping-Pong-Zyklen (Befehl A -> B -> A -> B)
  - Persistenten Fehlern (>= 3 aufeinanderfolgende Fehler)
- **Vollständiges Audit-Log:** Jeder ausgeführte oder abgelehnte Befehl wird mit Zeitstempel, Risiko-Level, Ausführungsdauer, Exit-Code und Ausgaben in der Room-Datenbank auditiert.

---

## 🛠️ Entwicklung & Build

### Voraussetzungen
- Android Studio Ladybug (oder neuer)
- JDK 21 (z. B. Eclipse Temurin 21)
- Android SDK Platform 35

### Befehle

```bash
cd android

# Alle Unit-Tests (Room DAOs, Engine, Security Filter, MockWebServer) ausführen
./gradlew testDebugUnitTest --no-daemon

# Debug-Build erstellen
./gradlew assembleDebug --no-daemon

# Signierten Release-Build erstellen
./gradlew assembleRelease --no-daemon
```

### CI/CD Pipeline
Die GitHub Actions Pipeline (`.github/workflows/android-ci.yml`) führt bei jedem Push und Pull Request automatisch:
- JDK 21 Setup mit Gradle Dependency Caching
- Ausführung aller Unit- & Integrationstests
- Kompilierung und Upload des Debug-APKs als Artefakt

---

## 🏗️ Projekt-Struktur

```
Aimovix-AMC/
├── .github/workflows/
│   └── android-ci.yml         # CI/CD Workflow für Tests & APK-Build
├── android/                   # Native Android App (Kotlin & Jetpack Compose)
│   ├── app/src/main/
│   │   ├── java/com/agent/mobile/
│   │   │   ├── agent/         # ReAct-Engine, Prompting & Loop-Detection Guardrails
│   │   │   ├── data/
│   │   │   │   ├── model/     # Datenmodelle (Tokens, Provider, Artefakte, Tools)
│   │   │   │   ├── network/   # SSE-Streaming LLM-Client, WebSocket Termux Bridge
│   │   │   │   ├── repository/# ChatRepository mit Room & Metriken
│   │   │   │   └── storage/   # EncryptedSharedPreferences & Room DB (DAOs, Entities)
│   │   │   ├── scheduler/     # WorkManager Background Worker & Scheduler
│   │   │   ├── security/      # 3-Tier Security Filter, Custom Regex & Strict Mode
│   │   │   ├── service/       # Android Foreground Service mit Live-Notification
│   │   │   └── ui/            # Modernes Zinc Dark UI (Chat, Drawer, Artifacts, Settings)
│   │   └── res/xml/           # network_security_config.xml
│   └── app/src/test/          # Unit- & Integrationstests (Room DAOs, MockWebServer, Engine)
├── termux-bridge/             # Termux Python Bridge Daemon & Setup Scripts
│   ├── bridge_daemon.py       # WebSocket Server (Befehle, Streaming, Base64-Dateitransfer, Cron)
│   ├── setup.sh               # 1-Klick Setup-Skript für Termux
│   └── local_model_manager.sh # llama.cpp & GGUF Modell-Manager
└── README.md
```
