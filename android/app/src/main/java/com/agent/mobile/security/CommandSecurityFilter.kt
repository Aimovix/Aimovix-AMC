package com.agent.mobile.security

import com.agent.mobile.data.model.ExecutionMode
import java.util.regex.Pattern

enum class RiskLevel(val displayName: String, val colorHex: Long) {
    LOW("Geringes Risiko", 0xFF00E676),
    MEDIUM("Mittleres Risiko", 0xFFFFB300),
    HIGH("Kritisches Risiko", 0xFFFF3366),
    BLOCKED("Sicherheits-Blockade", 0xFFD50000)
}

data class SecurityAssessment(
    val level: RiskLevel,
    val reason: String,
    val isBlocked: Boolean = level == RiskLevel.BLOCKED
)

object CommandSecurityFilter {

    // Blacklist: Commands that cause catastrophic, irreversible damage or malicious remote execution
    private val BLACKLIST_PATTERNS = listOf(
        Pattern.compile("rm\\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)\\s+(/(?:\\s+|$)|/\\*|~)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("rm\\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)\\s+\\*\\s*", Pattern.CASE_INSENSITIVE),
        Pattern.compile("mkfs(\\.[a-zA-Z0-9]+)?\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("dd\\s+if=/dev/(zero|urandom)\\s+of=/dev/", Pattern.CASE_INSENSITIVE),
        Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\};:", Pattern.CASE_INSENSITIVE), // Fork bomb
        Pattern.compile(">\\s*/dev/sd[a-z]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("chmod\\s+(-[a-zA-Z]*R\\s+)?000\\s+/", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(curl|wget)\\s+[^|]+\\|\\s*(ba)?sh\\b", Pattern.CASE_INSENSITIVE)
    )

    // High Risk: Data deletion, Telephony, SMS, Camera, System Reboot, Process Killing, Obfuscation & Dynamic Evaluation
    private val HIGH_RISK_PATTERNS = listOf(
        // Obfuscation & Dynamic execution guards
        Pair(Pattern.compile("\\bbase64\\s+(-d|--decode)\\b.*\\|\\s*(ba)?sh\\b", Pattern.CASE_INSENSITIVE), "Base64-kodierte Shell-Ausführung erkannt (Verschleierungsschutz)"),
        Pair(Pattern.compile("\\|\\s*(ba)?sh\\b", Pattern.CASE_INSENSITIVE), "Gepipter Shell-Befehl (dynamische Befehlsausführung)"),
        Pair(Pattern.compile("\\b(eval|exec)\\s+", Pattern.CASE_INSENSITIVE), "Dynamische Befehlsausführung via eval/exec"),
        Pair(Pattern.compile("\\b(python|python3|node|perl|ruby|sh|bash)\\s+(-c|-e)\\b", Pattern.CASE_INSENSITIVE), "Inline-Code-Ausführung über Interpreter"),
        Pair(Pattern.compile("\\b(xxd|hexdump)\\b.*\\|\\s*(ba)?sh\\b", Pattern.CASE_INSENSITIVE), "Hex-kodierte Shell-Ausführung erkannt"),

        // Sensitive Hardware, Privacy & System Actions
        Pair(Pattern.compile("\\brm\\b"), "Dateilöschung erkannt"),
        Pair(Pattern.compile("\\btermux-sms-send\\b"), "Versand einer SMS über dein Mobilfunknetz"),
        Pair(Pattern.compile("\\btermux-telephony-call\\b"), "Ausführung eines Telefonanrufs"),
        Pair(Pattern.compile("\\btermux-camera-photo\\b"), "Zugriff auf die Smartphone-Kamera"),
        Pair(Pattern.compile("\\btermux-contact-list\\b"), "Auslesen deiner privaten Kontakte"),
        Pair(Pattern.compile("\\b(pkill|kill|killall)\\b"), "Beenden von System- oder Hintergrundprozessen"),
        Pair(Pattern.compile("\\b(reboot|shutdown|poweroff)\\b"), "Systemneustart angefordert"),
        Pair(Pattern.compile("\\bchmod\\b"), "Berechtigungsänderung im Dateisystem"),
        Pair(Pattern.compile(">\\s*/dev/"), "Direkter Gerätezugriff"),
        Pair(Pattern.compile("\\btermux-storage-get\\b"), "Zugriff auf externen Telefonspeicher")
    )

    // Medium Risk: File modification, scripts, networking, package installation
    private val MEDIUM_RISK_PATTERNS = listOf(
        Pair(Pattern.compile("\\b(touch|mkdir|mv|cp)\\b"), "Dateisystem-Änderung"),
        Pair(Pattern.compile("\\b(python|python3|bash|sh|node)\\s+[^\\s-]+\\b"), "Ausführung einer Skriptdatei"),
        Pair(Pattern.compile("\\b(curl|wget)\\b"), "Netzwerkanfrage / Download"),
        Pair(Pattern.compile("\\b(pkg|apt|apt-get|pip)\\s+install\\b"), "Paket- oder Software-Installation"),
        Pair(Pattern.compile("\\bgit\\s+(clone|push|pull)\\b"), "Git Repository Operation"),
        Pair(Pattern.compile(">>|\\bcat\\s+<<"), "Dateierstellung / Anhängen von Daten")
    )

    fun analyze(command: String): SecurityAssessment {
        val trimmed = command.trim()
        val normalized = normalizeCommand(trimmed)

        // 1. Check Catastrophic Blacklist
        for (pattern in BLACKLIST_PATTERNS) {
            if (pattern.matcher(trimmed).find() || pattern.matcher(normalized).find()) {
                return SecurityAssessment(
                    level = RiskLevel.BLOCKED,
                    reason = "Befehl wurde aus Sicherheitsgründen hart blockiert (Potenziell destruktiv für das Gesamtsystem)."
                )
            }
        }

        // 2. Check High Risk
        for ((pattern, desc) in HIGH_RISK_PATTERNS) {
            if (pattern.matcher(trimmed).find() || pattern.matcher(normalized).find()) {
                return SecurityAssessment(
                    level = RiskLevel.HIGH,
                    reason = desc
                )
            }
        }

        // 3. Check Medium Risk
        for ((pattern, desc) in MEDIUM_RISK_PATTERNS) {
            if (pattern.matcher(trimmed).find() || pattern.matcher(normalized).find()) {
                return SecurityAssessment(
                    level = RiskLevel.MEDIUM,
                    reason = desc
                )
            }
        }

        // 4. Default to Low Risk (Read-only commands, diagnostics, info)
        return SecurityAssessment(
            level = RiskLevel.LOW,
            reason = "Sicherer Lese- oder Informationsbefehl"
        )
    }

    private fun normalizeCommand(command: String): String {
        // Strip quotes and escaping backslashes to thwart simple token-splitting obfuscation (e.g. r'm' or \rm)
        return command
            .replace("\\", "")
            .replace("'", "")
            .replace("\"", "")
    }

    /**
     * Determines whether user approval is mandatory before running the command.
     * Even in AUTOPILOT mode, HIGH risk commands ALWAYS enforce confirmation!
     */
    fun shouldRequireApproval(assessment: SecurityAssessment, mode: ExecutionMode): Boolean {
        if (assessment.isBlocked) return false // Blocked commands can never be approved
        if (mode == ExecutionMode.STEP_BY_STEP) return true
        // Autopilot Safety Guard: High Risk always requires approval!
        return assessment.level == RiskLevel.HIGH
    }
}
