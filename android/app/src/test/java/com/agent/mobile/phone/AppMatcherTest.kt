package com.agent.mobile.phone

import org.junit.Assert.*
import org.junit.Test

class AppMatcherTest {

    private val apps = listOf(
        AppEntry("WhatsApp", "com.whatsapp"),
        AppEntry("WhatsApp Business", "com.whatsapp.w4b"),
        AppEntry("Maps", "com.google.android.apps.maps"),
        AppEntry("Google Maps Go", "com.google.android.apps.navlite"),
        AppEntry("Sparkasse", "com.starfinanz.smob.android.sfinanzstatus"),
        AppEntry("Spotify", "com.spotify.music")
    )

    @Test
    fun exactNameAndPackageWin() {
        assertEquals("com.whatsapp", AppMatcher.bestMatch(apps, "whatsapp")!!.packageName)
        assertEquals("com.whatsapp.w4b", AppMatcher.bestMatch(apps, "com.whatsapp.w4b")!!.packageName)
        assertEquals("com.google.android.apps.maps", AppMatcher.bestMatch(apps, "Maps")!!.packageName)
    }

    @Test
    fun prefixAndContainsMatches() {
        assertEquals("com.spotify.music", AppMatcher.bestMatch(apps, "spot")!!.packageName)
        assertEquals("com.whatsapp.w4b", AppMatcher.bestMatch(apps, "business")!!.packageName)
        assertNull(AppMatcher.bestMatch(apps, "telegram"))
        assertNull(AppMatcher.bestMatch(apps, "  "))
    }

    @Test
    fun listMarksBlockedApps() {
        val text = AppMatcher.format(apps, null, PackageBlocklist.DEFAULT)
        assertTrue(text.startsWith("6 apps:"))
        assertTrue(text.contains("- Sparkasse (com.starfinanz.smob.android.sfinanzstatus) [blocked for AMC]"))
        assertTrue(text.contains("- Spotify (com.spotify.music)\n") || text.endsWith("- Spotify (com.spotify.music)"))
        assertFalse(text.contains("Spotify (com.spotify.music) [blocked"))
    }

    @Test
    fun listFilters() {
        assertTrue(AppMatcher.format(apps, "maps", PackageBlocklist.DEFAULT).startsWith("2 apps matching \"maps\""))
        assertEquals("No installed app matches \"zzz\".", AppMatcher.format(apps, "zzz", PackageBlocklist.DEFAULT))
    }
}
