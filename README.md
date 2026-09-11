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

Das Skript richtet die Umgebung automatisch ein:
- Aktiviert `termux-wake-lock`, um das Beenden des Prozesses durch Android im Standby zu verhindern.
- Installiert `python`, `termux-api`, `git`, `curl`, `jq` und `websockets`.
- Richtet den WebSocket-Bridge-Dienst ein und startet ihn auf `ws://127.0.0.1:8765`.
- Generiert einen sicheren Authentifizierungs-Token in `~/.termux_agent_token`.

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

## 🛠️ Entwicklung & Build

### Voraussetzungen
- Android Studio Ladybug (oder neuer)
- JDK 17+ (z. B. JetBrains Runtime 21)
- Android SDK Platform 35

### Befehle

```bash
cd android

# Unit-Tests für Security-Filter und Obfuskationserkennung ausführen
./gradlew test

# Debug-Build erstellen
./gradlew assembleDebug

# Signierten Release-Build erstellen (erzeugt APK unter app/build/outputs/apk/release/)
./gradlew assembleRelease
```

---

## 🏗️ Projekt-Struktur

```
Aimovix-AMC/
├── android/                   # Native Android App (Kotlin & Jetpack Compose)
│   ├── app/src/main/
│   │   ├── java/com/agent/mobile/
│   │   │   ├── agent/         # ReAct-Engine, Prompting & Injection-Guardrails
│   │   │   ├── data/          # WebSocket-Bridge, Multi-Provider LLM-Client, EncryptedStorage
│   │   │   ├── security/      # 3-Tier Security Filter & Obfuscation Guards
│   │   │   ├── service/       # Android Foreground Service & Wakelock
│   │   │   └── ui/            # Compose UI (Chat, Terminal, Setup, Settings)
│   │   └── res/xml/           # network_security_config.xml (Localhost-only Cleartext)
│   └── app/src/test/          # Automatisierte Unit-Tests für Sicherheitsfilter
├── termux-bridge/             # Termux Python Bridge Daemon & Setup Scripts
│   ├── bridge_daemon.py       # Asynchroner WebSocket-Bridge-Server
│   ├── setup.sh               # 1-Klick Setup-Skript für Termux
│   └── local_model_manager.sh # llama.cpp & GGUF Modell-Manager
└── README.md
```
