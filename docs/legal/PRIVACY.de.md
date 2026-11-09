# AMC – Datenschutzhinweise

_Version 3. Dieses Dokument ist keine Rechtsberatung._

## 1. Wer steht dahinter

AMC ist ein privates, nicht-kommerzielles Open-Source-Hobbyprojekt, veröffentlicht als **Aimovix** auf GitHub. Es gibt keine Firma und keine Einnahmen. Kontakt: [GitHub Issues](https://github.com/Aimovix/Aimovix-AMC/issues) (dort bitte keine persönlichen Daten oder API-Schlüssel posten).

Die Entwickler erhalten keine deiner Daten: AMC hat keine eigenen Server. Über die Verarbeitung in der App auf deinem Gerät bestimmst du selbst. Für Daten, die du an einen KI-Anbieter sendest, ist dieser Anbieter nach seiner eigenen Datenschutzerklärung verantwortlich.

## 2. Auf deinem Gerät gespeicherte Daten

| Daten | Zweck | Speicherort | Löschen |
| --- | --- | --- | --- |
| Chats, Werkzeug-Ausgaben, Audit-Protokoll | Verlauf, Nachvollziehbarkeit | App-Datenbank (privater App-Speicher) | Chats / Audit-Protokoll in der App löschen oder App deinstallieren |
| Langzeitgedächtnis (`MEMORY.md`) | Fakten, die sich der Agent merken soll | Privater App-Speicher | Einstellungen → Autonomy → Gedächtnis → Clear all |
| API-Schlüssel, Bridge-Token, Einstellungen | Verbindung zu Diensten | Verschlüsselte Einstellungen (Android Keystore, AES-256) | In den Einstellungen entfernen oder deinstallieren |
| Geplante Aufgaben | Hintergrundläufe | Android WorkManager | Einstellungen → Autonomy oder deinstallieren |
| Skill-Dateien (`~/.amc/skills`) | Wiederverwendbare Anleitungen | Termux-Home-Verzeichnis | Dateien in Termux löschen |
| Persona (`SOUL.md`) | Deine dauerhaften Anweisungen an den Agenten | Privater App-Speicher | Einstellungen → Autonomy → Persona (leeren und speichern) oder deinstallieren |
| Telegram-Bot-Token, gekoppelte Konten, Zuordnung Chat → Sitzung | Optionaler Telegram-Kanal | Verschlüsselte Einstellungen | Einstellungen → Channels (Konten entfernen, ausschalten) oder deinstallieren |
| Einstellungen der Handy-Steuerung: gesperrte und freigegebene Apps, Benachrichtigungs-Trigger, Schalter für Telegram und Hintergrund | Optionale Handy-Steuerung | Verschlüsselte Einstellungen | Einstellungen → Phone control oder deinstallieren |

Die Handy-Steuerung speichert Bildschirminhalte, Screenshots und Benachrichtigungen nicht von sich aus. Sie werden nur Teil des Chats (und gekürzt des Audit-Protokolls), wenn ein Werkzeug sie an den Agenten zurückgibt. Passwortfelder werden nie gelesen, eingetippte Passwörter werden nicht im Chat oder Audit-Protokoll gespeichert. Einmal-Codes in Benachrichtigungen werden ausgeblendet, bevor der Agent sie sieht.

App-Backups sind für AMC deaktiviert (`allowBackup=false`). AMC enthält keine Analyse-, Absturzbericht-, Werbe- oder Tracking-SDKs.

## 3. Übermittlung an Dritte

- **Von dir eingerichteter KI-Anbieter** (z. B. Google Gemini, OpenAI, Anthropic, Groq, OpenRouter): deine Nachrichten, angehängte Bilder, das Gedächtnis des Agenten, der Systemprompt und die Ausgaben der ausgeführten Werkzeuge. Diese können Dateiinhalte enthalten und – falls der Agent sie mit deiner Bestätigung liest – Nachrichten, Kontakte, Anruflisten, Fotos oder deinen Standort. Mit der **Handy-Steuerung** können sie auch Bildschirmtexte, Screenshots und Benachrichtigungen enthalten. Die Verarbeitung richtet sich nach den Bedingungen und der Datenschutzerklärung des Anbieters und kann außerhalb der EU/des EWR stattfinden. Mit einem **lokalen Modell** bleiben diese Daten auf deinem Gerät.
- **Webseiten**, die der Agent mit `web_fetch` oder `curl` öffnet, erhalten normale Anfragen von deinem Handy einschließlich deiner IP-Adresse.
- **DuckDuckGo** erhält die Suchbegriffe des Werkzeugs `web_search` und deine IP-Adresse.
- **Apps, die du den Agenten bedienen lässt**, erhalten Tipper und eingegebenen Text, als hättest du sie eingegeben. Antworten über Benachrichtigungen gehen an die App, die die Benachrichtigung gezeigt hat.
- **Telegram** (nur wenn du den Telegram-Kanal aktivierst): Nachrichten an deinen Bot, die Antworten des Agenten, Freigabeanfragen und Dateien, die der Agent dir schickt, werden über die Server von Telegram übertragen und nach dessen Datenschutzerklärung verarbeitet, möglicherweise außerhalb der EU/des EWR. Nur Telegram-Konten, die du mit einem Einmal-Code koppelst, können mit dem Agenten sprechen.
- **Termux-Bridge**: läuft nur auf deinem Handy (`127.0.0.1`) und erfordert ein Token. Über sie verlassen keine Daten das Gerät.

## 4. Daten anderer Personen

Nachrichten, Kontakte, Anruflisten, Fotos und Aufnahmen enthalten personenbezogene Daten anderer; ebenso der Bildschirm einer Messenger-App und deine Benachrichtigungen. AMC fragt vor dem Lesen oder Erstellen solcher Daten in jedem Sicherheitsmodus immer nach deiner Bestätigung (bei Messenger- und Kontakte-Apps einmal pro App und Aufgabe, beim Senden jedes Mal) (in der App oder per Buttons in deinem gekoppelten Telegram-Chat) und tut dies nie in unbeaufsichtigten Hintergrundläufen. Verarbeitest du sie über rein persönliche oder familiäre Zwecke hinaus, brauchst du eine Rechtsgrundlage (z. B. eine Einwilligung).

## 5. Berechtigungen

Internet (KI-Anbieter, Webseiten), Benachrichtigungen (Ergebnisse, Freigabeanfragen), Mikrofon (nur für die Spracheingabe, wenn du sie startest), Vordergrunddienst und Wake-Lock (Agent läuft weiter, während er arbeitet, und der optionale Telegram-Kanal bleibt verbunden), Start nach dem Hochfahren (Telegram-Kanal neu starten, falls aktiviert), Anfrage zur Akku-Optimierung (zuverlässiger Hintergrundbetrieb). Optional, nur wenn du sie einschaltest: Bedienungshilfe „AMC Phone Control“ (Bildschirm lesen und Apps bedienen, Screenshots ab Android 11), Benachrichtigungszugriff (Benachrichtigungen lesen, öffnen, beantworten und entfernen), Nutzungszugriff (Bildschirmzeit), Bildschirmaufnahme unter Android 8–10 (Android fragt jedes Mal), Shizuku (Befehle mit ADB-Rechten). Immer deklariert: installierte Apps sehen (um Apps per Name zu öffnen) und Wecker stellen. Termux und Termux:API haben eigene Android-Berechtigungen, die du separat erteilst.

## 6. Deine Rechte

Da die Entwickler keine deiner Daten speichern, richte Anfragen zu Daten bei einem KI-Anbieter an diesen Anbieter. Nach der DSGVO hast du das Recht auf Auskunft, Berichtigung, Löschung, Einschränkung, Datenübertragbarkeit und Widerspruch sowie das Recht auf Beschwerde bei einer Aufsichtsbehörde.

## 7. Änderungen

Ändern sich diese Hinweise wesentlich, bittet AMC dich, die neue Fassung zu akzeptieren.
