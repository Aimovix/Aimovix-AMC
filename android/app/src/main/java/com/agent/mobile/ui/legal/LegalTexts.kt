package com.agent.mobile.ui.legal

/**
 * Terms of use, disclaimer and privacy notice shown on first launch and in Settings.
 * Raise [VERSION] whenever the texts change materially so users accept the new version.
 *
 * Mirrors the Markdown files in docs/legal. This is a template, not legal advice: have it reviewed before
 * distributing the app publicly and fill in the operator details.
 */
object LegalTexts {
    const val VERSION = 3

    enum class Language { EN, DE }

    data class Section(val title: String, val body: String)

    fun sections(language: Language): List<Section> = if (language == Language.DE) DE else EN

    fun consentLabels(language: Language): List<String> = if (language == Language.DE) {
        listOf(
            "Ich bin mindestens 18 Jahre alt (oder habe die Zustimmung meiner Erziehungsberechtigten).",
            "Ich habe die Nutzungsbedingungen und den Haftungsausschluss gelesen und akzeptiere sie.",
            "Ich habe die Datenschutzhinweise gelesen. Mir ist bewusst, dass meine Eingaben und Werkzeug-Ausgaben an den von mir gewählten KI-Anbieter gesendet werden."
        )
    } else {
        listOf(
            "I am at least 18 years old (or have my parent's or guardian's permission).",
            "I have read and accept the terms of use and the disclaimer.",
            "I have read the privacy notice and understand that my input and tool output are sent to the AI provider I choose."
        )
    }

    private val EN = listOf(
        Section(
            "1. What AMC is",
            "AMC is a private, non-commercial open-source hobby project (no company, no income). It is experimental software that lets an AI model operate your phone through Termux: it can run commands, read and write files, open websites, send notifications and schedule its own background tasks. " +
                "If you turn on Phone control, it can also read the screen and operate apps, and read, open and answer notifications (see section 7). " +
                "AI models make mistakes. The agent can misunderstand you, act on wrong assumptions or be manipulated by content it reads (prompt injection). " +
                "Do not use AMC for medical, legal, financial or safety-critical decisions, and never as a replacement for emergency services."
        ),
        Section(
            "2. Your responsibility",
            "You decide which security mode, permissions and tasks you give the agent, and you are responsible for everything it does on your device and with your accounts. " +
                "This includes costs (mobile charges for SMS and calls, data usage, fees of your AI provider) and any data loss. " +
                "Review approval requests carefully, keep backups of important data, and prefer the Supervised mode when you are unsure."
        ),
        Section(
            "3. Lawful use only",
            "You may only use AMC in compliance with applicable law and the rights of others. In particular, do not use it to: send spam or unsolicited messages, harass or deceive anyone, " +
                "monitor, track, photograph or record other people without their consent, access systems or accounts without authorization, infringe copyrights or trademarks, " +
                "or collect data from websites against their terms. Messages, calls, contacts, photos and recordings involve other people's personal data: you need a legal basis or their consent. " +
                "For these actions AMC always asks for your confirmation, in every mode (in the app or, if you set up the Telegram channel, with buttons in your paired Telegram chat), and never performs them in unattended background runs."
        ),
        Section(
            "4. Third-party services",
            "AMC connects to services you choose and configure yourself (e.g. Google Gemini, OpenAI, Anthropic, Groq, OpenRouter, Termux and, optionally, a Telegram bot you create). The web_search tool uses DuckDuckGo. Their own terms, usage policies and privacy policies apply. " +
                "You need your own account and API key and must comply with the provider's rules. Product names belong to their respective owners; AMC is not affiliated with or endorsed by them."
        ),
        Section(
            "5. No warranty, limitation of liability",
            "AMC is provided free of charge \"as is\", without any warranty that it is error-free, secure or suitable for a particular purpose. " +
                "It is a gift: the developers do not earn anything from it. To the extent permitted by law, liability of the developers is excluded. This does not apply to liability for intent and gross negligence, for injury to life, body or health, " +
                "or to other mandatory statutory liability (such as under product liability law). Statutory consumer rights remain unaffected."
        ),
        Section(
            "6. Privacy notice (summary)",
            "The developers do not collect any data: AMC has no servers of its own and contains no analytics, advertising or tracking. Chats, the audit log and the agent's memory are stored only on your device; API keys are encrypted with the Android Keystore; app backups are disabled. " +
                "When you use a cloud AI provider, your messages, attached images, the agent's memory and the output of the tools it runs (which can include file contents, messages, contacts or location if the agent reads them, and, with Phone control, the text of the screen, screenshots and notifications) are sent to that provider and processed under its privacy policy, possibly outside the EU. " +
                "Websites the agent opens receive normal web requests from your phone; web searches send the query to DuckDuckGo. If you enable the Telegram channel, your messages to the agent, its replies, approval requests and files it sends you pass through Telegram's servers under Telegram's privacy policy. Use a local model to keep everything on your device. " +
                "You can delete chats and memory in the app at any time; uninstalling AMC removes all of its local data. The full privacy notice is in docs/legal/PRIVACY.md of the project."
        ),
        Section(
            "7. Phone control (screen and notifications)",
            "Phone control is off until you switch it on in Setup. The accessibility service \"AMC Phone Control\" lets the agent read the text and buttons of the app in front (never the content of password fields) and tap, type, scroll and swipe; on request it takes screenshots. Notification access lets it read, open, answer and dismiss notifications and start tasks you set up when certain notifications arrive. " +
                "It only acts on tasks you give it, while an \"AMC is in control\" banner with a Stop button is visible. Sending messages (also in apps such as WhatsApp), payments and purchases, installing or removing apps, deleting data, logins, calls, photos and changes to system settings always need your confirmation, in every mode. " +
                "Banking, payment, password manager and authenticator apps and security settings are blocked by default; you can release single apps in Settings, at your own risk. AMC never unlocks or bypasses the lock screen. In Telegram conversations and background tasks screen control is off unless you switch it on. " +
                "Screen content, screenshots and notifications can contain other people's messages and personal data. They are processed like other tool output (see section 6). Only let the agent read them for your own purposes; the rules of section 3 apply. You can switch Phone control off at any time in Android's accessibility and notification settings."
        ),
        Section(
            "8. Changes",
            "If these terms change materially, AMC asks you to accept the new version before you continue."
        )
    )

    private val DE = listOf(
        Section(
            "1. Was AMC ist",
            "AMC ist ein privates, nicht-kommerzielles Open-Source-Hobbyprojekt (keine Firma, keine Einnahmen). Es ist experimentelle Software, mit der ein KI-Modell dein Handy über Termux bedient: Es kann Befehle ausführen, Dateien lesen und schreiben, Webseiten öffnen, Benachrichtigungen senden und eigene Hintergrundaufgaben planen. " +
                "Wenn du die Handy-Steuerung einschaltest, kann er außerdem den Bildschirm lesen, Apps bedienen sowie Benachrichtigungen lesen, öffnen und beantworten (siehe Abschnitt 7). " +
                "KI-Modelle machen Fehler. Der Agent kann dich missverstehen, von falschen Annahmen ausgehen oder durch gelesene Inhalte manipuliert werden (Prompt Injection). " +
                "Nutze AMC nicht für medizinische, rechtliche, finanzielle oder sicherheitskritische Entscheidungen und niemals als Ersatz für Notrufdienste."
        ),
        Section(
            "2. Deine Verantwortung",
            "Du entscheidest, welchen Sicherheitsmodus, welche Berechtigungen und welche Aufgaben du dem Agenten gibst, und bist für alles verantwortlich, was er auf deinem Gerät und mit deinen Konten tut. " +
                "Dazu gehören Kosten (Gebühren für SMS und Anrufe, Datenvolumen, Gebühren deines KI-Anbieters) und Datenverluste. " +
                "Prüfe Freigabeanfragen sorgfältig, sichere wichtige Daten und nutze im Zweifel den Modus „Supervised“."
        ),
        Section(
            "3. Nur rechtmäßige Nutzung",
            "Du darfst AMC nur im Einklang mit geltendem Recht und den Rechten anderer nutzen. Insbesondere nicht, um Spam oder unerwünschte Nachrichten zu senden, jemanden zu belästigen oder zu täuschen, " +
                "andere Personen ohne deren Einwilligung zu überwachen, zu orten, zu fotografieren oder aufzunehmen, unbefugt auf Systeme oder Konten zuzugreifen, Urheber- oder Markenrechte zu verletzen " +
                "oder Daten entgegen den Nutzungsbedingungen von Webseiten zu sammeln. Nachrichten, Anrufe, Kontakte, Fotos und Aufnahmen betreffen personenbezogene Daten anderer: Du brauchst dafür eine Rechtsgrundlage oder deren Einwilligung. " +
                "Für diese Aktionen fragt AMC in jedem Modus immer nach deiner Bestätigung (in der App oder, wenn du den Telegram-Kanal eingerichtet hast, per Buttons in deinem gekoppelten Telegram-Chat) und führt sie nie in unbeaufsichtigten Hintergrundläufen aus."
        ),
        Section(
            "4. Dienste Dritter",
            "AMC verbindet sich mit Diensten, die du selbst auswählst und einrichtest (z. B. Google Gemini, OpenAI, Anthropic, Groq, OpenRouter, Termux und optional ein von dir erstellter Telegram-Bot). Das Werkzeug web_search nutzt DuckDuckGo. Es gelten deren Nutzungsbedingungen, Nutzungsrichtlinien und Datenschutzerklärungen. " +
                "Du brauchst ein eigenes Konto und einen eigenen API-Schlüssel und musst die Regeln des Anbieters einhalten. Produktnamen gehören ihren jeweiligen Inhabern; AMC steht mit ihnen in keiner Verbindung und wird von ihnen nicht unterstützt."
        ),
        Section(
            "5. Keine Gewährleistung, Haftungsbeschränkung",
            "AMC wird unentgeltlich und „wie besehen“ bereitgestellt, ohne Zusicherung, dass es fehlerfrei, sicher oder für einen bestimmten Zweck geeignet ist. " +
                "AMC ist eine Schenkung: Die Entwickler verdienen nichts daran. Die Haftung richtet sich daher nach den Regeln für Schenkungen (§§ 521, 524 BGB) und ist, soweit gesetzlich zulässig, ausgeschlossen. Dies gilt nicht für Haftung wegen Vorsatz und grober Fahrlässigkeit, für Schäden aus der Verletzung des Lebens, des Körpers oder der Gesundheit " +
                "sowie für sonstige zwingende gesetzliche Haftung (z. B. nach dem Produkthaftungsgesetz). Gesetzliche Verbraucherrechte bleiben unberührt."
        ),
        Section(
            "6. Datenschutzhinweise (Kurzfassung)",
            "Die Entwickler erheben keine Daten: AMC hat keine eigenen Server und enthält keine Analyse-, Werbe- oder Tracking-Funktionen. Chats, Audit-Protokoll und das Gedächtnis des Agenten werden nur auf deinem Gerät gespeichert; API-Schlüssel sind mit dem Android Keystore verschlüsselt; App-Backups sind deaktiviert. " +
                "Wenn du einen Cloud-KI-Anbieter nutzt, werden deine Nachrichten, angehängte Bilder, das Gedächtnis des Agenten und die Ausgaben der ausgeführten Werkzeuge (darunter Dateiinhalte, Nachrichten, Kontakte oder Standort, falls der Agent sie liest, und mit der Handy-Steuerung Bildschirmtexte, Screenshots und Benachrichtigungen) an diesen Anbieter gesendet und nach dessen Datenschutzerklärung verarbeitet, möglicherweise außerhalb der EU. " +
                "Webseiten, die der Agent öffnet, erhalten normale Anfragen von deinem Handy; Websuchen senden den Suchbegriff an DuckDuckGo. Wenn du den Telegram-Kanal aktivierst, laufen deine Nachrichten an den Agenten, seine Antworten, Freigabeanfragen und Dateien, die er dir schickt, über die Server von Telegram (es gilt deren Datenschutzerklärung). Mit einem lokalen Modell bleiben alle Daten auf deinem Gerät. " +
                "Chats und Gedächtnis kannst du jederzeit in der App löschen; bei der Deinstallation werden alle lokalen Daten von AMC entfernt. Die vollständigen Datenschutzhinweise stehen im Projekt unter docs/legal/PRIVACY.de.md."
        ),
        Section(
            "7. Handy-Steuerung (Bildschirm und Benachrichtigungen)",
            "Die Handy-Steuerung ist aus, bis du sie im Setup einschaltest. Die Bedienungshilfe „AMC Phone Control“ lässt den Agenten Texte und Schaltflächen der App im Vordergrund lesen (nie den Inhalt von Passwortfeldern) und tippen, Text eingeben, scrollen und wischen; auf Anfrage macht sie Screenshots. Mit dem Benachrichtigungszugriff kann er Benachrichtigungen lesen, öffnen, beantworten und entfernen und von dir eingerichtete Aufgaben starten, wenn bestimmte Benachrichtigungen eintreffen. " +
                "Er handelt nur bei Aufgaben, die du ihm gibst, und nur, solange der Hinweis „AMC steuert“ mit Stopp-Knopf sichtbar ist. Nachrichten senden (auch in Apps wie WhatsApp), Zahlungen und Käufe, Apps installieren oder entfernen, Daten löschen, Anmeldungen, Anrufe, Fotos und Änderungen an Systemeinstellungen brauchen in jedem Modus immer deine Bestätigung. " +
                "Banking-, Zahlungs-, Passwortmanager- und Authenticator-Apps sowie Sicherheitseinstellungen sind standardmäßig gesperrt; einzelne Apps kannst du in den Einstellungen auf eigenes Risiko freigeben. AMC entsperrt oder umgeht nie den Sperrbildschirm. In Telegram-Unterhaltungen und Hintergrundaufgaben ist die Bildschirmsteuerung aus, solange du sie nicht einschaltest. " +
                "Bildschirminhalte, Screenshots und Benachrichtigungen können Nachrichten und personenbezogene Daten anderer enthalten. Sie werden wie andere Werkzeug-Ausgaben verarbeitet (siehe Abschnitt 6). Lass den Agenten sie nur für eigene Zwecke lesen; es gelten die Regeln aus Abschnitt 3. Du kannst die Handy-Steuerung jederzeit in den Android-Einstellungen für Bedienungshilfen und Benachrichtigungen ausschalten."
        ),
        Section(
            "8. Änderungen",
            "Ändern sich diese Bedingungen wesentlich, bittet AMC dich, die neue Fassung zu akzeptieren, bevor du weitermachst."
        )
    )
}
