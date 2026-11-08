package com.agent.mobile.ui.legal

/**
 * Terms of use, disclaimer and privacy notice shown on first launch and in Settings.
 * Raise [VERSION] whenever the texts change materially so users accept the new version.
 *
 * Mirrors the Markdown files in docs/legal. This is a template, not legal advice: have it reviewed before
 * distributing the app publicly and fill in the operator details.
 */
object LegalTexts {
    const val VERSION = 1

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
            "AMC is experimental software that lets an AI model operate your phone through Termux: it can run commands, read and write files, open websites, send notifications and schedule its own background tasks. " +
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
                "For these actions AMC always asks for your confirmation, in every mode, and never performs them in unattended background runs."
        ),
        Section(
            "4. Third-party services",
            "AMC connects to services you choose and configure yourself (e.g. Google Gemini, OpenAI, Anthropic, Groq, OpenRouter, Termux). Their own terms, usage policies and privacy policies apply. " +
                "You need your own account and API key and must comply with the provider's rules. Product names belong to their respective owners; AMC is not affiliated with or endorsed by them."
        ),
        Section(
            "5. No warranty, limitation of liability",
            "AMC is provided free of charge \"as is\", without any warranty that it is error-free, secure or suitable for a particular purpose. " +
                "To the extent permitted by law, liability of the developers is excluded. This does not apply to liability for intent and gross negligence, for injury to life, body or health, " +
                "or to other mandatory statutory liability (such as under product liability law). Statutory consumer rights remain unaffected."
        ),
        Section(
            "6. Privacy notice (summary)",
            "AMC has no servers of its own and contains no analytics, advertising or tracking. Chats, the audit log and the agent's memory are stored only on your device; API keys are encrypted with the Android Keystore; app backups are disabled. " +
                "When you use a cloud AI provider, your messages, attached images, the agent's memory and the output of the tools it runs (which can include file contents, messages, contacts or location if the agent reads them) are sent to that provider and processed under its privacy policy, possibly outside the EU. " +
                "Websites the agent opens receive normal web requests from your phone. Use a local model to keep everything on your device. " +
                "You can delete chats and memory in the app at any time; uninstalling AMC removes all of its local data. The full privacy notice is in docs/legal/PRIVACY.md of the project."
        ),
        Section(
            "7. Changes",
            "If these terms change materially, AMC asks you to accept the new version before you continue."
        )
    )

    private val DE = listOf(
        Section(
            "1. Was AMC ist",
            "AMC ist experimentelle Software, mit der ein KI-Modell dein Handy über Termux bedient: Es kann Befehle ausführen, Dateien lesen und schreiben, Webseiten öffnen, Benachrichtigungen senden und eigene Hintergrundaufgaben planen. " +
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
                "Für diese Aktionen fragt AMC in jedem Modus immer nach deiner Bestätigung und führt sie nie in unbeaufsichtigten Hintergrundläufen aus."
        ),
        Section(
            "4. Dienste Dritter",
            "AMC verbindet sich mit Diensten, die du selbst auswählst und einrichtest (z. B. Google Gemini, OpenAI, Anthropic, Groq, OpenRouter, Termux). Es gelten deren Nutzungsbedingungen, Nutzungsrichtlinien und Datenschutzerklärungen. " +
                "Du brauchst ein eigenes Konto und einen eigenen API-Schlüssel und musst die Regeln des Anbieters einhalten. Produktnamen gehören ihren jeweiligen Inhabern; AMC steht mit ihnen in keiner Verbindung und wird von ihnen nicht unterstützt."
        ),
        Section(
            "5. Keine Gewährleistung, Haftungsbeschränkung",
            "AMC wird unentgeltlich und „wie besehen“ bereitgestellt, ohne Zusicherung, dass es fehlerfrei, sicher oder für einen bestimmten Zweck geeignet ist. " +
                "Soweit gesetzlich zulässig, ist die Haftung der Entwickler ausgeschlossen. Dies gilt nicht für Haftung wegen Vorsatz und grober Fahrlässigkeit, für Schäden aus der Verletzung des Lebens, des Körpers oder der Gesundheit " +
                "sowie für sonstige zwingende gesetzliche Haftung (z. B. nach dem Produkthaftungsgesetz). Gesetzliche Verbraucherrechte bleiben unberührt."
        ),
        Section(
            "6. Datenschutzhinweise (Kurzfassung)",
            "AMC hat keine eigenen Server und enthält keine Analyse-, Werbe- oder Tracking-Funktionen. Chats, Audit-Protokoll und das Gedächtnis des Agenten werden nur auf deinem Gerät gespeichert; API-Schlüssel sind mit dem Android Keystore verschlüsselt; App-Backups sind deaktiviert. " +
                "Wenn du einen Cloud-KI-Anbieter nutzt, werden deine Nachrichten, angehängte Bilder, das Gedächtnis des Agenten und die Ausgaben der ausgeführten Werkzeuge (darunter Dateiinhalte, Nachrichten, Kontakte oder Standort, falls der Agent sie liest) an diesen Anbieter gesendet und nach dessen Datenschutzerklärung verarbeitet, möglicherweise außerhalb der EU. " +
                "Webseiten, die der Agent öffnet, erhalten normale Anfragen von deinem Handy. Mit einem lokalen Modell bleiben alle Daten auf deinem Gerät. " +
                "Chats und Gedächtnis kannst du jederzeit in der App löschen; bei der Deinstallation werden alle lokalen Daten von AMC entfernt. Die vollständigen Datenschutzhinweise stehen im Projekt unter docs/legal/PRIVACY.de.md."
        ),
        Section(
            "7. Änderungen",
            "Ändern sich diese Bedingungen wesentlich, bittet AMC dich, die neue Fassung zu akzeptieren, bevor du weitermachst."
        )
    )
}
