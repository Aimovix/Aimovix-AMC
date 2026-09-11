# 🤖 Aimovix – Autonomer Android KI-Agent

**Aimovix** ist eine native Android-App (Kotlin + Jetpack Compose), die dein Smartphone in einen vollautonomen mobilen KI-Agenten verwandelt. Der Agent steuert eine lokale Termux-Linux-Umgebung und erhält über `termux-api` sowie Shell-Befehle tiefgreifenden System- und Hardware-Zugriff (SMS, Kamera, GPS, Benachrichtigungen, Sensoren, Dateisystem, Python-Skripte).

---

## 📱 Schnellstart-Anleitung

### 1. App auf dem Smartphone installieren
Kopiere die fertige APK auf dein Android-Smartphone und installiere sie:
- Download direkt im Repository: **`Aimovix.apk`**

### 2. Termux & Termux:API installieren
Installiere beide Apps über **F-Droid** (wichtig: die Version aus dem Google Play Store ist veraltet):
1. **[Termux auf F-Droid](https://f-droid.org/packages/com.termux/)**
2. **[Termux:API auf F-Droid](https://f-droid.org/packages/com.termux.api/)**

### 3. Der 1-Klick-Setup-Befehl in Termux
Öffne Termux auf deinem Smartphone und führe folgenden Befehl aus (oder nutze den "Kopieren"-Button im Setup-Tab der Aimovix-App):

```bash
curl -sL https://raw.githubusercontent.com/Aimovix/Aimovix/main/termux-bridge/setup.sh | bash
```

Das Skript erledigt automatisch:
- Aktiviert `termux-wake-lock`, damit der Agent im Standby nicht von Android gestoppt wird.
- Installiert `python`, `termux-api`, `git`, `curl`, `jq` und `websockets`.
- Richtet den schnellen WebSocket-Bridge-Dienst ein und startet ihn auf `ws://127.0.0.1:8765`.

---

## 🧠 KI-Modell wählen

In der Aimovix-App unter dem Tab **"Einstellungen"**:
1. **Lokale KI (Offline)**:
   - Wähle **"Lokaler Server"** (`http://127.0.0.1:8080/v1`).
   - Starte in Termux über `bash termux-bridge/local_model_manager.sh` den `llama-server` (z. B. mit Qwen 2.5 1.5B oder 3B GGUF).
2. **Cloud-APIs**:
   - Unterstützt **Google Gemini**, **OpenAI**, **Anthropic Claude**, **Groq** und **OpenRouter**.
   - Trage einfach deinen API-Key ein.

---

## ⚡ Bedienung & Autonomie

- **Autopilot-Modus**: Der Agent führt alle nötigen Einzelschritte selbstständig aus, wertet Ausgaben und Fehler aus und korrigiert sich selbst.
- **Schritt-für-Schritt-Freigabe**: Der Agent schlägt jeden Befehl vor und wartet auf deinen Klick (`Ausführen` oder `Ablehnen`).
- **Live-Terminal-Streaming**: Jeder Befehl zeigt live die stdout/stderr-Ausgaben in einer aufklappbaren Terminal-Box direkt im Chat.
- **Not-Aus-Button**: Ein roter Schwebeknopf bricht die Ausführung und laufende Prozesse sofort ab.

### Beispiel-Aufträge:
- *"Prüfe meinen Akkustand und schicke mir eine Benachrichtigung, wenn er unter 30% ist."*
- *"Mache ein Foto mit der Hauptkamera und speichere es im Download-Ordner."*
- *"Schreibe ein Python-Skript, das die aktuellen Bitcoin-Kurse abfragt und das Ergebnis ausgibt."*
- *"Lies die letzten 3 empfangenen SMS vor."*

---

## 🏗️ Projekt-Struktur

```
Aimovix/
├── Aimovix.apk                # Installationsfertige Android-App
├── android/                   # Native Android App (Kotlin & Jetpack Compose)
│   ├── app/src/main/java/com/agent/mobile/
│   │   ├── agent/             # ReAct-Engine, Prompting, Autopilot-Logik
│   │   ├── data/              # WebSocket-Client, Multi-Provider LLM-Client
│   │   ├── service/           # Android Foreground Service
│   │   └── ui/                # Compose UI (Chat, Terminal, Setup, Settings)
├── termux-bridge/             # Termux Python Bridge Daemon & Setup Scripts
│   ├── bridge_daemon.py       # Asynchroner WebSocket-Bridge-Server
│   ├── setup.sh               # 1-Klick Setup-Skript für Termux
│   └── local_model_manager.sh # llama.cpp & GGUF Modell-Manager
└── README.md
```
