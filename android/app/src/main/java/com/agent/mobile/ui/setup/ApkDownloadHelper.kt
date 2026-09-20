package com.agent.mobile.ui.setup

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast

object ApkDownloadHelper {
    const val TERMUX_APK_URL = "https://f-droid.org/repo/com.termux_1022.apk"
    const val TERMUX_API_APK_URL = "https://f-droid.org/repo/com.termux.api_1002.apk"

    fun downloadTermux(context: Context) {
        downloadApk(
            context = context,
            appName = "Termux",
            filename = "com.termux_1022.apk",
            url = TERMUX_APK_URL
        )
    }

    fun downloadTermuxApi(context: Context) {
        downloadApk(
            context = context,
            appName = "Termux:API",
            filename = "com.termux.api_1002.apk",
            url = TERMUX_API_APK_URL
        )
    }

    private fun downloadApk(context: Context, appName: String, filename: String, url: String) {
        var downloadEnqueued = false
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (dm != null) {
                val uri = Uri.parse(url)
                val request = DownloadManager.Request(uri)
                    .setTitle("Downloading $appName")
                    .setDescription("Official $appName APK")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                    .setMimeType("application/vnd.android.package-archive")
                dm.enqueue(request)
                downloadEnqueued = true
                Toast.makeText(
                    context,
                    "Downloading $appName APK. Check your notifications to install.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            downloadEnqueued = false
        }

        if (!downloadEnqueued) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(browserIntent)
                Toast.makeText(context, "Opening direct download for $appName...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Could not start download: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
