package com.agent.mobile.phone

/**
 * Only one run operates the screen at a time. The interactive chat, a scheduled task and a
 * Telegram conversation could otherwise tap into the same app at once. The first screen tool of a
 * run takes the lease; other runs get "screen busy" right away instead of interleaving their
 * taps. The lease ends with the run, or after [idleMs] without a screen tool (a crashed run must
 * not block the screen forever).
 */
class ScreenLease(
    private val idleMs: Long = 120_000L,
    private val clock: () -> Long = { android.os.SystemClock.uptimeMillis() }
) {
    private var owner: String? = null
    private var lastUsed = 0L

    /** Takes or renews the lease for [runId]. Returns null on success, else why the screen is busy. */
    @Synchronized
    fun acquire(runId: String): String? {
        val now = clock()
        val current = owner
        if (current == null || current == runId || now - lastUsed > idleMs) {
            owner = runId
            lastUsed = now
            return null
        }
        val waitS = ((idleMs - (now - lastUsed)) / 1000).coerceAtLeast(1)
        return "Screen busy: another AMC task is operating the phone right now. Nothing was done; try again later " +
            "(the other task releases the screen when it ends, at the latest in about $waitS s of inactivity)."
    }

    @Synchronized
    fun release(runId: String) {
        if (owner == runId) owner = null
    }

    @Synchronized
    fun holder(): String? = owner
}
