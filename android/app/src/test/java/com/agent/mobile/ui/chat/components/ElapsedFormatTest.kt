package com.agent.mobile.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ElapsedFormatTest {

    @Test
    fun secondsUnderOneMinute() {
        assertEquals("0 s", formatElapsed(0))
        assertEquals("4 s", formatElapsed(4_999))
        assertEquals("59 s", formatElapsed(59_000))
    }

    @Test
    fun minutesAndSeconds() {
        assertEquals("1 min 0 s", formatElapsed(60_000))
        assertEquals("2 min 5 s", formatElapsed(125_000))
    }

    @Test
    fun negativeTimesClampToZero() {
        assertEquals("0 s", formatElapsed(-5_000))
    }
}
