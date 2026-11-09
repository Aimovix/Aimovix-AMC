package com.agent.mobile.phone

import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher

/**
 * One background thread for every accessibility node access and gesture callback.
 *
 * Reading the node tree is binder IPC into the app in front: thousands of calls, each of which
 * can block for seconds when that app hangs. On the main thread this froze accessibility events,
 * gesture callbacks and the overlay (so settle() waited for nothing and the Stop pill stayed
 * touchable). A single thread also keeps node access ordered.
 */
object PhoneThread {
    private val thread: HandlerThread by lazy { HandlerThread("amc-phone").apply { start() } }

    val handler: Handler by lazy { Handler(thread.looper) }

    val dispatcher: CoroutineDispatcher by lazy { handler.asCoroutineDispatcher("amc-phone") }
}
