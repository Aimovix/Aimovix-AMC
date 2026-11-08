# AMC – Datenschutzhinweise

_Version 1. Vorlage: Platzhalter in Abschnitt 1 ausfüllen und den Text vor einer Veröffentlichung (z. B. im App-Store) prüfen lassen. Dieses Dokument ist keine Rechtsberatung._

## 1. Verantwortlicher

AMC wird entwickelt von **[NAME / FIRMA]**, **[ANSCHRIFT]**, **[E-MAIL]**.

Die Entwickler erhalten keine deiner Daten: AMC hat keine eigenen Server. Über die Verarbeitung in der App auf deinem Gerät bestimmst du selbst. Für Daten, die du an einen KI-Anbieter sendest, ist dieser Anbieter nach seiner eigenen Datenschutzerklärung verantwortlich.

## 2. Auf deinem Gerät gespeicherte Daten

| Daten | Zweck | Speicherort | Löschen |
| --- | --- | --- | --- |
| Chats, Werkzeug-Ausgaben, Audit-Protokoll | Verlauf, Nachvollziehbarkeit | App-Datenbank (privater App-Speicher) | Chats / Audit-Protokoll in der App löschen oder App deinstallieren |
| Langzeitgedächtnis (`MEMORY.md`) | Fakten, die sich der Agent merken soll | Privater App-Speicher | Einstellungen → Autonomy → Gedächtnis → Clear all |
| API-Schlüssel, Bridge-Token, Einstellungen | Verbindung zu Diensten | Verschlüsselte Einstellungen (Android Keystore, AES-256) | In den Einstellungen entfernen oder deinstallieren |
| Geplante Aufgaben | Hintergrundläufe | Android WorkManager | Einstellungen → Autonomy oder deinstallieren |
| Skill-Dateien (`~/.amc/skills`) | Wiederverwendbare Anleitungen | Termux-Home-Verzeichnis | Dateien in Termux löschen |

App-Backups sind für AMC deaktiviert (`allowBackup=false`). AMC enthält keine Analyse-, Absturzbericht-, Werbe- oder Tracking-SDKs.

## 3. Übermittlung an Dritte

- **Von dir eingerichteter KI-Anbieter** (z. B. Google Gemini, OpenAI, Anthropic, Groq, OpenRouter): deine Nachrichten, angehängte Bilder, das Gedächtnis des Agenten, der Systemprompt und die Ausgaben der ausgeführten Werkzeuge. Diese können Dateiinhalte enthalten und – falls der Agent sie mit deiner Bestätigung liest – Nachrichten, Kontakte, Anruflisten, Fotos oder deinen Standort. Die Verarbeitung richtet sich nach den Bedingungen und der Datenschutzerklärung des Anbieters und kann außerhalb der EU/des EWR stattfinden. Mit einem **lokalen Modell** bleiben diese Daten auf deinem Gerät.
- **Webseiten**, die der Agent mit `web_fetch` oder `curl` öffnet, erhalten normale Anfragen von deinem Handy einschließlich deiner IP-Adresse.
- **Termux-Bridge**: läuft nur auf deinem Handy (`127.0.0.1`) und erfordert ein Token. Über sie verlassen keine Daten das Gerät.

## 4. Daten anderer Personen

Nachrichten, Kontakte, Anruflisten, Fotos und Aufnahmen enthalten personenbezogene Daten anderer. AMC fragt vor dem Lesen oder Erstellen solcher Daten in jedem Sicherheitsmodus immer nach deiner Bestätigung und tut dies nie in unbeaufsichtigten Hintergrundläufen. Verarbeitest du sie über rein persönliche oder familiäre Zwecke hinaus, brauchst du eine Rechtsgrundlage (z. B. eine Einwilligung).

## 5. Berechtigungen

Internet (KI-Anbieter, Webseiten), Benachrichtigungen (Ergebnisse, Freigabeanfragen), Mikrofon (nur für die Spracheingabe, wenn du sie startest), Vordergrunddienst und Wake-Lock (Agent läuft weiter, während er arbeitet), Anfrage zur Akku-Optimierung (zuverlässiger Hintergrundbetrieb). Termux und Termux:API haben eigene Android-Berechtigungen, die du separat erteilst.

## 6. Deine Rechte

Da die Entwickler keine deiner Daten speichern, richte Anfragen zu Daten bei einem KI-Anbieter an diesen Anbieter. Nach der DSGVO hast du das Recht auf Auskunft, Berichtigung, Löschung, Einschränkung, Datenübertragbarkeit und Widerspruch sowie das Recht auf Beschwerde bei einer Aufsichtsbehörde.

## 7. Änderungen

Ändern sich diese Hinweise wesentlich, bittet AMC dich, die neue Fassung zu akzeptieren.
