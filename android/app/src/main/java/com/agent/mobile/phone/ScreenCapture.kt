package com.agent.mobile.phone

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.agent.mobile.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Screenshots for vision models.
 * - Android 11+: [AccessibilityService.takeScreenshot]; windows with FLAG_SECURE are refused by the system.
 * - Android 8–10: MediaProjection, after the system's own consent dialog ("Start recording or casting?").
 *   The projection stays open while the agent controls the phone and is closed when control ends.
 * Frames whose app area is fully black (protected windows) are never sent.
 */
object ScreenCapture {

    sealed class Result {
        class Image(val base64Jpeg: String, val width: Int, val height: Int) : Result()
        class Refused(val reason: String) : Result()
    }

    suspend fun capture(context: Context, service: PhoneControlService): Result {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (val r = accessibilityScreenshot(service)) {
                is Shot.Ok -> r.bitmap
                is Shot.Failed -> return Result.Refused(r.reason)
            }
        } else {
            ProjectionCapture.grab(context) ?: return Result.Refused(
                "No screenshot: the screen capture permission was not granted (Android asks each time control starts)."
            )
        }
        return encode(bitmap)
    }

    private sealed class Shot {
        class Ok(val bitmap: Bitmap) : Shot()
        class Failed(val reason: String) : Shot()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun accessibilityScreenshot(service: PhoneControlService): Shot {
        repeat(2) { attempt ->
            val result = takeOnce(service)
            if (result is Shot.Failed && result.reason == INTERVAL && attempt == 0) {
                delay(1_100)
            } else {
                return result
            }
        }
        return Shot.Failed("Screenshots are limited to one per second. Try again.")
    }

    private const val INTERVAL = "interval"

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeOnce(service: PhoneControlService): Shot = suspendCancellableCoroutine { cont ->
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val shot = try {
                        val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        screenshot.hardwareBuffer.close()
                        if (copy == null) Shot.Failed("The screenshot could not be read.") else Shot.Ok(copy)
                    } catch (e: Exception) {
                        Shot.Failed("The screenshot could not be read: ${e.message}")
                    }
                    if (cont.isActive) cont.resume(shot)
                }

                override fun onFailure(errorCode: Int) {
                    val reason = when (errorCode) {
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> INTERVAL
                        // ERROR_TAKE_SCREENSHOT_SECURE_WINDOW (Android 14+)
                        5 -> "No screenshot: the app in front protects its window (FLAG_SECURE). Use screen_read instead."
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                            "No screenshot: AMC Phone Control is not allowed to take screenshots."
                        else -> "No screenshot (system error $errorCode)."
                    }
                    if (cont.isActive) cont.resume(Shot.Failed(reason))
                }
            }
        )
    }

    /** Scales, checks for a protected (black) frame and encodes as base64 JPEG. */
    private fun encode(source: Bitmap): Result {
        val (w, h) = ScreenshotSupport.targetSize(source.width, source.height)
        val scaled = if (w == source.width && h == source.height) source else Bitmap.createScaledBitmap(source, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (ScreenshotSupport.isProtectedFrame(pixels, w, h)) {
            return Result.Refused("No screenshot: the app in front shows a protected (FLAG_SECURE) window. Use screen_read instead.")
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, ScreenshotSupport.JPEG_QUALITY, out)
        return Result.Image(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), w, h)
    }

    /** Ends the MediaProjection (Android 8–10) when phone control ends. */
    fun release(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) ProjectionCapture.stop(context)
    }
}

/** MediaProjection path for Android 8–10. */
internal object ProjectionCapture {
    private const val TAG = "ProjectionCapture"

    @Volatile
    var pendingConsent: CompletableDeferred<Pair<Int, Intent?>>? = null

    @Volatile
    var service: ScreenCaptureService? = null

    suspend fun grab(context: Context): Bitmap? {
        service?.let { svc -> return svc.grabFrame() }
        // Ask for the system's consent; the activity hands the result to the service.
        val consent = CompletableDeferred<Pair<Int, Intent?>>()
        pendingConsent = consent
        context.startActivity(
            Intent(context, ScreenCaptureConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val (code, data) = withTimeoutOrNull(60_000L) { consent.await() } ?: return null
        pendingConsent = null
        if (code != Activity.RESULT_OK || data == null) return null
        val started = CompletableDeferred<ScreenCaptureService?>()
        ScreenCaptureService.onReady = { started.complete(it) }
        val intent = Intent(context, ScreenCaptureService::class.java)
            .putExtra(ScreenCaptureService.EXTRA_CODE, code)
            .putExtra(ScreenCaptureService.EXTRA_DATA, data)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the capture service: ${e.message}")
            return null
        }
        val svc = withTimeoutOrNull(10_000L) { started.await() } ?: return null
        return svc.grabFrame()
    }

    fun stop(context: Context) {
        if (service != null) context.stopService(Intent(context, ScreenCaptureService::class.java))
    }
}

/** Invisible activity that shows the system's screen capture consent dialog (Android 8–10). */
class ScreenCaptureConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        ProjectionCapture.pendingConsent?.complete(resultCode to data)
        finish()
    }

    private companion object {
        const val REQUEST = 7301
    }
}

/** Holds the MediaProjection while phone control is active (foreground service, type mediaProjection). */
class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "amc_screen_capture"
        private const val NOTIFICATION_ID = 1102

        @Volatile
        var onReady: ((ScreenCaptureService?) -> Unit)? = null
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WrongConstant")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = if (data != null) manager.getMediaProjection(code, data) else null
        if (p == null) {
            onReady?.invoke(null)
            stopSelf()
            return START_NOT_STICKY
        }
        projection = p
        val metrics = resources.displayMetrics
        val (w, h) = realSize()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        display = p.createVirtualDisplay(
            "amc-screen", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
        ProjectionCapture.service = this
        onReady?.invoke(this)
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun realSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val m = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    /** Latest frame as a bitmap (waits briefly for the first one). */
    suspend fun grabFrame(): Bitmap? {
        val r = reader ?: return null
        repeat(20) {
            val image = r.acquireLatestImage()
            if (image != null) {
                try {
                    val plane = image.planes[0]
                    val rowPadding = plane.rowStride - plane.pixelStride * image.width
                    val padded = Bitmap.createBitmap(image.width + rowPadding / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    return Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                } finally {
                    image.close()
                }
            }
            delay(100)
        }
        return null
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.phone_control_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.screen_capture_notification_title))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        display?.release()
        reader?.close()
        projection?.stop()
        display = null
        reader = null
        projection = null
        if (ProjectionCapture.service === this) ProjectionCapture.service = null
        super.onDestroy()
    }
}
