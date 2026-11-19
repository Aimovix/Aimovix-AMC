# Feature-Vergleich mit großen KI-Apps (Teil E)

Stand: 2026-11-19, AMC 1.9.0. Vorbilder sind nur in der **Funktion** gemeint (Gemini-App, Antigravity,
ChatGPT, Claude); Aussehen, Namen, Farben und Layouts bleiben bei AMC (Calm Design, Feature 25 in PROJECT.md).

**Legende:** Status = vorhanden / teilweise / fehlt (vor dieser Runde). Nutzen und Aufwand: hoch / mittel / niedrig.
Spalte „Umgesetzt" nennt, was in dieser Runde gebaut wurde.

## Inventar (was AMC schon kann)

Chat mit Streaming und Bildern (Kamera/Galerie, Vision-Modelle), Spracheingabe, mehrere Anbieter (OpenAI-kompatibel,
Claude, Gemini, lokales Modell) mit Fallback, Kosten-/Token-Anzeige, Sitzungen mit Suche, Export (Markdown/JSON) und
Wischen-zum-Löschen mit Rückgängig, Langdruck-Menü (Kopieren, erneut senden), Artefakt-Ansicht (Bilder, Text, Code,
Markdown, HTML), Langzeitgedächtnis mit Editor, Persona (SOUL.md), Skills, Websuche und Web-Abruf, Terminal-Tab,
Zeitpläne, Heartbeat, Notification-Trigger, Telegram-Kanal, Sub-Agenten, Handy-Steuerung (Bedienungshilfe, Shizuku),
Hintergrundjobs, Unbeaufsichtigt-Modus mit Laufbericht, manipulationssicheres Audit-Protokoll, Richtlinien-Simulator.

## Tabelle

| Funktion | Vorbild | Status vorher | Nutzen | Aufwand | Umgesetzt |
|---|---|---|---|---|---|
| **Chat-Grundlagen** | | | | | |
| Antworten als formatiertes Markdown (Überschriften, Listen, fett/kursiv, Links) | alle | fehlt (reiner Text) | hoch | mittel | ✅ E1 |
| Codeblöcke mit eigenem Kopier-Knopf | alle | fehlt | hoch | niedrig | ✅ E1 |
| Nachricht bearbeiten und neu senden | ChatGPT, Claude, Gemini | teilweise (nur unverändert erneut senden) | mittel | niedrig | ✅ E5 |
| Antwort neu erzeugen | alle | teilweise (über „erneut senden") | mittel | mittel | – |
| Verzweigte Unterhaltungen (Varianten wechseln) | ChatGPT, Claude | fehlt | niedrig | hoch | – |
| Folgefragen-Vorschläge | Gemini | teilweise (nur leerer Chat) | niedrig | mittel | – |
| Sitzungen suchen, exportieren, löschen | alle | vorhanden | – | – | – |
| Sitzungen anheften / umbenennen | alle | fehlt | mittel | mittel | – |
| **Eingabe** | | | | | |
| Spracheingabe | alle | vorhanden | – | – | – |
| Bilder anhängen | alle | vorhanden | – | – | – |
| Dateien (PDF, Text) anhängen | alle | teilweise (über Termux-Pfade) | mittel | mittel | – |
| Vorlesen von Antworten | Gemini, ChatGPT | fehlt | mittel | niedrig | ✅ E4 |
| Gespeicherte Prompt-Vorlagen | ChatGPT (GPTs), Claude (Projekte) | teilweise (Schnellaktionen, Skills) | mittel | mittel | – |
| **Android-Integration** | | | | | |
| „Teilen an …" aus anderen Apps (Text, Links, Bilder) | Gemini, ChatGPT | fehlt | hoch | niedrig | ✅ E2 |
| App-Shortcuts (Langdruck auf das Symbol) | Gemini, ChatGPT | fehlt | mittel | niedrig | ✅ E3 |
| Schnelleinstellungs-Kachel | Gemini | fehlt | mittel | niedrig | ✅ E3 („Stop all agents") |
| Startbildschirm-Widget | Gemini, ChatGPT | fehlt | niedrig | mittel | – |
| Als Standard-Assistent (Assist-Geste) | Gemini | fehlt | mittel | hoch | – |
| **Agenten-Funktionen** | | | | | |
| Übersicht aller laufenden und letzten Agenten-Läufe mit Stopp und Bericht („Inbox") | Antigravity | teilweise (Werkzeug `list_runs`, Audit-Zeitachse) | hoch | mittel | ✅ E6 |
| Parallele Läufe | Antigravity | vorhanden (Chat, Hintergrund, Telegram; Sub-Agenten parallel seit #25) | – | – | – |
| Artefakte (Dateien, Berichte) | Claude, Antigravity | vorhanden (Artefakt-Ansicht, `send_file`) | – | – | – |
| Laufberichte | Antigravity | vorhanden (Unbeaufsichtigt-Laufbericht) | – | – | – |
| Wissensbasis / Projektdateien | Claude (Projekte), ChatGPT | teilweise (Skills, Gedächtnis) | mittel | hoch | – |
| Aufgaben planen | ChatGPT (Tasks), Gemini | vorhanden (Zeitpläne, Bedingungen seit #23) | – | – | – |
| **Personalisierung** | | | | | |
| Eigene Anweisungen / Persona | alle | vorhanden (SOUL.md) | – | – | – |
| Gedächtnis ansehen und bearbeiten | ChatGPT, Gemini, Claude | vorhanden | – | – | – |
| Lernen aus Fehlern | – | vorhanden seit #24 | – | – | – |
| **Qualität** | | | | | |
| Modellwechsel im Chat | alle | vorhanden | – | – | – |
| Kosten-/Tokenanzeige | – | vorhanden | – | – | – |
| Barrierefreiheit (TalkBack-Texte, 48-dp-Ziele) | alle | teilweise (laufend verbessert, #36) | mittel | mittel | teilweise (E1–E6 mit Beschriftungen) |

## Auswahl (bestes Verhältnis Nutzen/Aufwand)

- **E1 Markdown und Codeblöcke:** jede große App rendert Antworten formatiert; ohne das sind Code und Listen schwer lesbar.
- **E2 Teilen an AMC:** wichtigster Einstieg aus anderen Apps (Link, Text, Foto → Aufgabe), kleiner Aufwand.
- **E3 Shortcuts und Kachel:** schneller Start einer neuen Aufgabe und Notbremse für alle Agenten von überall.
- **E4 Vorlesen:** Android-TTS, ohne neue Rechte, hilft unterwegs und für Barrierefreiheit.
- **E5 Bearbeiten und neu senden:** häufigste Korrektur im Chat, kleiner Aufwand.
- **E6 Lauf-Übersicht:** passt zum Ziel „lange, unbeaufsichtigte Läufe": was läuft, was lief, mit Stopp und Bericht.

Nicht umgesetzt (Aufwand zu hoch oder Nutzen zu klein für diese Runde): Verzweigungen, Widget, Assist-Geste,
Wissensbasis, Datei-Anhänge außerhalb von Termux, Anheften/Umbenennen – siehe Liste für die nächste Sitzung im Worklog.
