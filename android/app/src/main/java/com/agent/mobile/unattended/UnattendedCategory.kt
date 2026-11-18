package com.agent.mobile.unattended

/**
 * Kinds of actions that would ask the user in the Autonomous preset. In unattended mode each
 * category is decided by a fixed rule instead of a question: allowed (and logged) or denied (and
 * logged, with a hint so the model works around it). The floor (catastrophic shell targets,
 * banking/password/authenticator apps, Android security settings, AMC's own settings, token and
 * persona) is not a category: it is always blocked.
 */
enum class UnattendedCategory(
    val id: String,
    val title: String,
    val description: String,
    val defaultAllowed: Boolean
) {
    FILE_DELETE("file_delete", "Delete files", "Deleting files and folders in the Termux home or working folder.", true),
    PROCESS_CONTROL("process_control", "Stop processes", "Killing processes, restarting services, reboot requests.", true),
    PACKAGE_INSTALL("package_install", "Install packages", "pkg, apt and pip installs in Termux.", true),
    CAMERA_MIC("camera_mic", "Camera and microphone", "Taking photos, recording audio, operating the camera app.", true),
    LOCATION("location", "Location", "Reading the phone's location.", true),
    SYSTEM_SETTINGS("system_settings", "System settings", "Changing system settings and system dialogs, except security settings.", true),
    APP_CONTROL("app_control", "Operate apps", "Reading and operating apps, including messengers and contacts apps.", true),
    DELETE_APP_DATA("delete_app_data", "Delete in apps", "Delete buttons inside apps, clearing an app's data.", true),
    NOTIFICATIONS("notifications", "Notifications", "Reading notifications and the notification shade.", true),
    SEND_MESSAGES("send_messages", "Send messages", "SMS, messenger and e-mail messages, notification replies, posts.", true),
    PRIVATE_DATA("private_data", "Private data", "Reading SMS, contacts, call history and the clipboard.", true),
    ADVANCED_SHELL("advanced_shell", "Advanced shell", "Encoded or indirect commands, eval, device files.", true),
    PAYMENTS("payments", "Payments and purchases", "Paying, buying, ordering, subscribing, booking, transferring money.", false),
    CALLS("calls", "Phone calls", "Starting phone or video calls.", false),
    ACCOUNTS("accounts", "Accounts and logins", "Logging in or out, passwords, account changes, credential files.", false),
    STORE_INSTALLS("store_installs", "App installs (store)", "Installing, updating or uninstalling apps through a store or installer.", false);

    companion object {
        fun byId(id: String): UnattendedCategory? = entries.firstOrNull { it.id == id }

        /** Shown in the opt-in dialog and the settings: never allowed without supervision. */
        val FLOOR: List<String> = listOf(
            "Catastrophic shell targets (root, home, shared storage roots)",
            "Banking, payment, password and authenticator apps (even if released for supervised use)",
            "Android security settings (screen lock, accounts, device admins, accessibility, developer options)",
            "AMC itself: its own settings and permissions, the bridge token and the persona"
        )
    }
}

/** Per-run limits of unattended runs. 0 means no limit. */
data class UnattendedLimits(
    val maxDurationMinutes: Int = 360,
    val maxPhoneActions: Int = 300,
    val maxMessagesSent: Int = 20
)

/** The user's unattended settings: allowed categories and limits. */
data class UnattendedSettings(
    val allowed: Set<UnattendedCategory> = UnattendedCategory.entries.filter { it.defaultAllowed }.toSet(),
    val limits: UnattendedLimits = UnattendedLimits()
) {
    fun isAllowed(category: UnattendedCategory): Boolean = category in allowed

    fun toJson(): String = org.json.JSONObject().apply {
        put("allowed", org.json.JSONArray(allowed.map { it.id }.sorted()))
        put("maxDurationMinutes", limits.maxDurationMinutes)
        put("maxPhoneActions", limits.maxPhoneActions)
        put("maxMessagesSent", limits.maxMessagesSent)
    }.toString()

    companion object {
        fun fromJson(text: String?): UnattendedSettings {
            if (text.isNullOrBlank()) return UnattendedSettings()
            return try {
                val o = org.json.JSONObject(text)
                val arr = o.optJSONArray("allowed")
                val allowed = if (arr == null) UnattendedSettings().allowed
                else (0 until arr.length()).mapNotNull { UnattendedCategory.byId(arr.optString(it)) }.toSet()
                val d = UnattendedLimits()
                UnattendedSettings(
                    allowed = allowed,
                    limits = UnattendedLimits(
                        maxDurationMinutes = o.optInt("maxDurationMinutes", d.maxDurationMinutes).coerceIn(0, 24 * 60),
                        maxPhoneActions = o.optInt("maxPhoneActions", d.maxPhoneActions).coerceIn(0, 10_000),
                        maxMessagesSent = o.optInt("maxMessagesSent", d.maxMessagesSent).coerceIn(0, 1_000)
                    )
                )
            } catch (e: Exception) {
                UnattendedSettings()
            }
        }
    }
}
