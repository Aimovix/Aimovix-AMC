package com.agent.mobile.phone

/**
 * Apps the agent must not operate (read, tap, type, open, reply to their notifications) unless
 * the user releases them one by one in Settings → Phone control.
 *
 * Entry forms, one per line:
 * - `com.example.app`            the whole app
 * - `com.example.*`              every package with that prefix
 * - `com.example.app/ClassPrefix` only windows whose class starts with the prefix
 * - `settings:security`           security pages of the system settings (screen lock, passwords,
 *                                 device admins, accessibility, developer options, …)
 *
 * Apps whose name looks like banking, payment, password or authenticator apps are blocked too,
 * even when they are not listed, unless their package is in [allowed].
 */
data class PackageBlocklist(
    val entries: List<String> = DEFAULT_ENTRIES,
    /** Packages the user released individually; they override the name heuristic and listed entries. */
    val allowed: Set<String> = emptySet()
) {
    val blocksSecuritySettings: Boolean get() = SECURITY_SETTINGS in entries

    /**
     * Why [packageName] (with [appLabel] and the current [windowClass]) is blocked, or null when
     * the agent may operate it.
     */
    fun blockReason(packageName: String?, appLabel: String? = null, windowClass: String? = null): String? {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isNotEmpty() && pkg in allowed) return null
        for (entry in entries) {
            if (pkg.isEmpty()) break
            if (entry == SECURITY_SETTINGS) continue
            val (entryPkg, classPrefix) = entry.split('/', limit = 2).let { it[0] to it.getOrNull(1) }
            val pkgMatches = if (entryPkg.endsWith(".*")) pkg.startsWith(entryPkg.removeSuffix("*")) || pkg == entryPkg.removeSuffix(".*")
            else pkg == entryPkg
            if (!pkgMatches) continue
            if (classPrefix != null) {
                val cls = windowClass.orEmpty()
                val fullPrefix = if (classPrefix.startsWith(".")) entryPkg + classPrefix else classPrefix
                if (cls.isEmpty() || !cls.startsWith(fullPrefix)) continue
            }
            return (CATEGORY_OF[entry] ?: "App") + " on the AMC block list"
        }
        val label = appLabel?.trim().orEmpty()
        if (label.isNotEmpty() && SENSITIVE_LABEL.containsMatchIn(label)) {
            return "\"$label\" looks like a banking, payment, password or authenticator app"
        }
        return null
    }

    fun isBlocked(packageName: String?, appLabel: String? = null, windowClass: String? = null): Boolean =
        blockReason(packageName, appLabel, windowClass) != null

    /** Adds [entry] to the list (and removes it from the released packages). */
    fun withEntry(entry: String): PackageBlocklist {
        val e = normalize(entry) ?: return this
        return copy(entries = (entries + e).distinct(), allowed = allowed - e.substringBefore('/'))
    }

    /** Removes [entry]; a package entry is also released so the name heuristic does not block it again. */
    fun withoutEntry(entry: String): PackageBlocklist {
        val e = entry.trim()
        val pkg = e.substringBefore('/')
        val release = e != SECURITY_SETTINGS && !pkg.endsWith("*") && !e.contains('/')
        return copy(entries = entries - e, allowed = if (release) allowed + pkg else allowed)
    }

    companion object {
        const val SECURITY_SETTINGS = "settings:security"

        private val ENTRY_REGEX = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)*(\.\*)?(/[A-Za-z0-9_.$]+)?$""")

        /** Validates and trims one entry; null when it is not a package, prefix or known rule. */
        fun normalize(entry: String): String? {
            val e = entry.trim()
            if (e.isEmpty()) return null
            if (e == SECURITY_SETTINGS) return e
            return if (ENTRY_REGEX.matches(e) && e.contains('.')) e else null
        }

        /** Parses stored text (one entry per line, `#` comments ignored). */
        fun parseEntries(text: String): List<String> =
            text.lines().map { it.substringBefore('#').trim() }.mapNotNull { normalize(it) }.distinct()

        private val BANKING = listOf(
            "com.starfinanz.smob.android.sfinanzstatus", // Sparkasse
            "com.starfinanz.mobile.android.pushtan",     // S-pushTAN
            "de.ing_diba.kontostand",
            "de.dkb.portalapp",
            "de.number26.android",
            "de.comdirect.android",
            "de.commerzbanking.mobil",
            "de.postbank.finanzassistent",
            "de.fiducia.smartphone.android.banking.vr",
            "com.db.pwcc.dbmobile",
            "com.bunq.android",
            "com.revolut.revolut",
            "com.transferwise.android",
            "de.traderepublic.app",
            "com.chase.sig.android",
            "com.infonow.bofa",
            "com.wf.wellsfargomobile",
            "com.paypal.android.p2pmobile",
            "com.klarna.android",
            "com.google.android.apps.walletnfcrel",
            "com.samsung.android.spay",
            "com.coinbase.android",
            "com.binance.dev"
        )
        private val PASSWORD_MANAGERS = listOf(
            "com.x8bit.bitwarden",
            "com.onepassword.android",
            "com.agilebits.onepassword",
            "com.lastpass.lpandroid",
            "com.dashlane",
            "keepass2android.keepass2android",
            "keepass2android.keepass2android_nonet",
            "com.kunzisoft.keepass.free",
            "com.kunzisoft.keepass.libre",
            "io.enpass.app",
            "com.callpod.android_apps.keeper",
            "proton.android.pass"
        )
        private val AUTHENTICATORS = listOf(
            "com.google.android.apps.authenticator2",
            "com.azure.authenticator",
            "com.authy.authy",
            "org.fedorahosted.freeotp",
            "com.beemdevelopment.aegis",
            "com.yubico.yubioath",
            "org.shadowice.flocke.andotp",
            "com.twofasapp",
            "de.governikus.ausweisapp2"
        )
        /** Google Play purchase dialogs (the rest of the Play Store stays usable; installs always ask). */
        private const val PLAY_BILLING = "com.android.vending/com.google.android.finsky.billing"

        private val CATEGORY_OF: Map<String, String> =
            BANKING.associateWith { "Banking or payment app" } +
                PASSWORD_MANAGERS.associateWith { "Password manager" } +
                AUTHENTICATORS.associateWith { "Authenticator or ID app" } +
                mapOf(PLAY_BILLING to "Google Play payment dialog")

        val DEFAULT_ENTRIES: List<String> = BANKING + PASSWORD_MANAGERS + AUTHENTICATORS + listOf(PLAY_BILLING, SECURITY_SETTINGS)

        val DEFAULT = PackageBlocklist()

        /** App names that indicate money, credentials or second factors. */
        val SENSITIVE_LABEL = Regex(
            """\b(bank|banking|sparkasse|volksbank|raiffeisen|paypal|wallet|pay|pushtan|tan|authenticator|authentifikator|2fa|otp|password|passwords|passwort|kennwort|keepass\w*|bitwarden|1password|lastpass|vault|tresor|broker|depot|trading|crypto|krypto)\b""",
            RegexOption.IGNORE_CASE
        )

        /** Words in the title area of a settings page that mark it as security-relevant. */
        val SECURITY_SETTINGS_TITLES = Regex(
            """\b(security|sicherheit|screen lock|bildschirmsperre|displaysperre|lock screen|sperrbildschirm|passwords?|passw(ö|oe)rter|passkeys?|device admin\w*|ger(ä|ae)teadministrator\w*|device management|fingerprint|fingerabdruck|face unlock|gesichtserkennung|biometric\w*|biometrie|encryption|verschl(ü|ue)sselung|developer options|entwickleroptionen|unknown apps|unbekannte apps|accessibility|bedienungshilfen|special app access|spezieller app-zugriff|factory reset|zur(ü|ue)cksetzen|accounts?|konten|google account|privacy|datenschutz|permission manager|berechtigungsmanager|smart lock|trust agents?|sim lock|sim-sperre|credential|anmeldedaten)\b""",
            RegexOption.IGNORE_CASE
        )
    }
}
