# AMC – Nutzungsbedingungen und Haftungsausschluss

_Version 3. Wird beim ersten Start in der App angezeigt (Einstellungen → Legal & privacy). Keine Rechtsberatung._

## 1. Was AMC ist

AMC ist ein privates, nicht-kommerzielles Open-Source-Hobbyprojekt (keine Firma, keine Einnahmen). Es ist experimentelle Software, mit der ein KI-Modell dein Handy über Termux bedient: Es kann Befehle ausführen, Dateien lesen und schreiben, Webseiten öffnen, Benachrichtigungen senden und eigene Hintergrundaufgaben planen. Wenn du die Handy-Steuerung einschaltest, kann er außerdem den Bildschirm lesen, Apps bedienen sowie Benachrichtigungen lesen, öffnen und beantworten (siehe Abschnitt 7). KI-Modelle machen Fehler. Der Agent kann dich missverstehen, von falschen Annahmen ausgehen oder durch gelesene Inhalte manipuliert werden (Prompt Injection). Nutze AMC nicht für medizinische, rechtliche, finanzielle oder sicherheitskritische Entscheidungen und niemals als Ersatz für Notrufdienste.

## 2. Deine Verantwortung

Du entscheidest, welchen Sicherheitsmodus, welche Berechtigungen und welche Aufgaben du dem Agenten gibst, und bist für alles verantwortlich, was er auf deinem Gerät und mit deinen Konten tut. Dazu gehören Kosten (Gebühren für SMS und Anrufe, Datenvolumen, Gebühren deines KI-Anbieters) und Datenverluste. Prüfe Freigabeanfragen sorgfältig, sichere wichtige Daten und nutze im Zweifel den Modus „Supervised“.

## 3. Nur rechtmäßige Nutzung

Du darfst AMC nur im Einklang mit geltendem Recht und den Rechten anderer nutzen. Insbesondere nicht, um Spam oder unerwünschte Nachrichten zu senden, jemanden zu belästigen oder zu täuschen, andere Personen ohne deren Einwilligung zu überwachen, zu orten, zu fotografieren oder aufzunehmen, unbefugt auf Systeme oder Konten zuzugreifen, Urheber- oder Markenrechte zu verletzen oder Daten entgegen den Nutzungsbedingungen von Webseiten zu sammeln. Nachrichten, Anrufe, Kontakte, Fotos und Aufnahmen betreffen personenbezogene Daten anderer: Du brauchst dafür eine Rechtsgrundlage oder deren Einwilligung. Für diese Aktionen fragt AMC in jedem Modus immer nach deiner Bestätigung (in der App oder, wenn du den Telegram-Kanal eingerichtet hast, per Buttons in deinem gekoppelten Telegram-Chat) und führt sie nie in unbeaufsichtigten Hintergrundläufen aus.

## 4. Dienste Dritter

AMC verbindet sich mit Diensten, die du selbst auswählst und einrichtest (z. B. Google Gemini, OpenAI, Anthropic, Groq, OpenRouter, Termux und optional ein von dir erstellter Telegram-Bot). Das Werkzeug web_search nutzt DuckDuckGo. Es gelten deren Nutzungsbedingungen, Nutzungsrichtlinien und Datenschutzerklärungen. Du brauchst ein eigenes Konto und einen eigenen API-Schlüssel und musst die Regeln des Anbieters einhalten. Produktnamen gehören ihren jeweiligen Inhabern; AMC steht mit ihnen in keiner Verbindung und wird von ihnen nicht unterstützt.

## 5. Keine Gewährleistung, Haftungsbeschränkung

AMC wird unentgeltlich und „wie besehen“ bereitgestellt, ohne Zusicherung, dass es fehlerfrei, sicher oder für einen bestimmten Zweck geeignet ist. AMC ist eine Schenkung: Die Entwickler verdienen nichts daran. Die Haftung richtet sich daher nach den Regeln für Schenkungen (§§ 521, 524 BGB) und ist, soweit gesetzlich zulässig, ausgeschlossen. Dies gilt nicht für Haftung wegen Vorsatz und grober Fahrlässigkeit, für Schäden aus der Verletzung des Lebens, des Körpers oder der Gesundheit sowie für sonstige zwingende gesetzliche Haftung (z. B. nach dem Produkthaftungsgesetz). Gesetzliche Verbraucherrechte bleiben unberührt.

## 6. Datenschutzhinweise (Kurzfassung)

Die Entwickler erheben keine Daten: AMC hat keine eigenen Server und enthält keine Analyse-, Werbe- oder Tracking-Funktionen. Chats, Audit-Protokoll und das Gedächtnis des Agenten werden nur auf deinem Gerät gespeichert; API-Schlüssel sind mit dem Android Keystore verschlüsselt; App-Backups sind deaktiviert. Wenn du einen Cloud-KI-Anbieter nutzt, werden deine Nachrichten, angehängte Bilder, das Gedächtnis des Agenten und die Ausgaben der ausgeführten Werkzeuge (darunter Dateiinhalte, Nachrichten, Kontakte oder Standort, falls der Agent sie liest, und mit der Handy-Steuerung Bildschirmtexte, Screenshots und Benachrichtigungen) an diesen Anbieter gesendet und nach dessen Datenschutzerklärung verarbeitet, möglicherweise außerhalb der EU. Webseiten, die der Agent öffnet, erhalten normale Anfragen von deinem Handy; Websuchen senden den Suchbegriff an DuckDuckGo. Wenn du den Telegram-Kanal aktivierst, laufen deine Nachrichten an den Agenten, seine Antworten, Freigabeanfragen und Dateien, die er dir schickt, über die Server von Telegram (es gilt deren Datenschutzerklärung). Mit einem lokalen Modell bleiben alle Daten auf deinem Gerät. Chats und Gedächtnis kannst du jederzeit in der App löschen; bei der Deinstallation werden alle lokalen Daten von AMC entfernt. Die vollständigen Datenschutzhinweise stehen im Projekt unter docs/legal/PRIVACY.de.md.

## 7. Handy-Steuerung (Bildschirm und Benachrichtigungen)

Die Handy-Steuerung ist aus, bis du sie im Setup einschaltest. Die Bedienungshilfe „AMC Phone Control“ lässt den Agenten Texte und Schaltflächen der App im Vordergrund lesen (nie den Inhalt von Passwortfeldern) und tippen, Text eingeben, scrollen und wischen; auf Anfrage macht sie Screenshots. Mit dem Benachrichtigungszugriff kann er Benachrichtigungen lesen, öffnen, beantworten und entfernen und von dir eingerichtete Aufgaben starten, wenn bestimmte Benachrichtigungen eintreffen. Er handelt nur bei Aufgaben, die du ihm gibst, und nur, solange der Hinweis „AMC steuert“ mit Stopp-Knopf sichtbar ist. Nachrichten senden (auch in Apps wie WhatsApp), Zahlungen und Käufe, Apps installieren oder entfernen, Daten löschen, Anmeldungen, Anrufe, Fotos und Änderungen an Systemeinstellungen brauchen in jedem Modus immer deine Bestätigung. Banking-, Zahlungs-, Passwortmanager- und Authenticator-Apps sowie Sicherheitseinstellungen sind standardmäßig gesperrt; einzelne Apps kannst du in den Einstellungen auf eigenes Risiko freigeben. AMC entsperrt oder umgeht nie den Sperrbildschirm. In Telegram-Unterhaltungen und Hintergrundaufgaben ist die Bildschirmsteuerung aus, solange du sie nicht einschaltest. Bildschirminhalte, Screenshots und Benachrichtigungen können Nachrichten und personenbezogene Daten anderer enthalten. Sie werden wie andere Werkzeug-Ausgaben verarbeitet (siehe Abschnitt 6). Lass den Agenten sie nur für eigene Zwecke lesen; es gelten die Regeln aus Abschnitt 3. Du kannst die Handy-Steuerung jederzeit in den Android-Einstellungen für Bedienungshilfen und Benachrichtigungen ausschalten.

## 8. Änderungen

Ändern sich diese Bedingungen wesentlich, bittet AMC dich, die neue Fassung zu akzeptieren, bevor du weitermachst.
