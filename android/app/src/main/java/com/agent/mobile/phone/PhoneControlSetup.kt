package com.agent.mobile.phone

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/** System facts and settings pages for the phone control setup steps. */
object PhoneControlSetup {
    private const val PREFS = "amc_setup"
    private const val KEY_RESTRICTED_VISITED = "restricted_settings_visited"
    private const val OP_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings"

    /**
     * Android 13+ blocks accessibility and notification access for apps that were not installed
     * by a store until the user allows "restricted settings" in App info. AMC is installed from
     * GitHub, so this usually applies.
     */
    fun restrictedSettingsApply(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            context.packageManager.getInstallSourceInfo(context.packageName).packageSource != PackageInstaller.PACKAGE_SOURCE_STORE
        } catch (e: Exception) {
            true
        }
    }

    /** Best effort: the app-op says allowed, or the user already went to App info from the checklist. */
    fun restrictedSettingsAllowed(context: Context): Boolean {
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RESTRICTED_VISITED, false)) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            ops.unsafeCheckOpNoThrow(OP_ACCESS_RESTRICTED_SETTINGS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    fun markRestrictedSettingsVisited(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RESTRICTED_VISITED, true).apply()
    }

    fun hasUsageAccess(context: Context): Boolean = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    fun appInfoIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** The accessibility page of AMC Phone Control where possible, otherwise the accessibility list. */
    fun accessibilityIntents(context: Context): List<Intent> {
        val component = ComponentName(context, PhoneControlService::class.java).flattenToString()
        return listOf(
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, component),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        )
    }

    fun notificationAccessIntents(context: Context): List<Intent> {
        val component = ComponentName(context, AmcNotificationListener::class.java).flattenToString()
        val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component))
        } else emptyList()
        return detail + Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    fun usageAccessIntents(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    )

    /** Starts the first intent some app can handle. Returns false when none could be opened. */
    fun startFirst(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) continue
            try {
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // try the next one
            }
        }
        return false
    }
}
