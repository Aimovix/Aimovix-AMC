package com.agent.mobile.core.termux

import android.content.Context
import android.os.Build

/**
 * Android 12+ kills "phantom" child processes of background apps (more than 32 in total), which
 * ends the Termux bridge and long commands. It can only be switched off with ADB rights, so it is
 * an optional setup step: through Shizuku with one tap, otherwise as an ADB command to copy.
 * The agent itself never runs these commands (see PrivilegedCommandPolicy).
 */
object PhantomProcessKiller {
    private const val PREFS = "amc_setup"
    private const val KEY_HANDLED = "phantom_killer_handled"

    /** The commands, each run as its own process (no shell). */
    val COMMANDS: List<List<String>> = listOf(
        listOf("/system/bin/device_config", "set_sync_disabled_for_tests", "persistent"),
        listOf("/system/bin/device_config", "put", "activity_manager", "max_phantom_processes", "2147483647"),
        listOf("settings", "put", "global", "settings_enable_monitor_phantom_procs", "false")
    )

    /** The same as one command for a computer with ADB. */
    val ADB_COMMAND: String =
        "adb shell \"" + COMMANDS.joinToString("; ") { it.joinToString(" ") } + "\""

    fun applies(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.S

    fun isHandled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HANDLED, false)

    fun markHandled(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HANDLED, true).apply()
    }
}
