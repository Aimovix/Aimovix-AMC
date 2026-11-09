package com.agent.mobile.phone

import org.junit.Assert.*
import org.junit.Test

class PackageBlocklistTest {

    private val list = PackageBlocklist.DEFAULT

    @Test
    fun defaultsBlockBankingPasswordAndAuthenticatorApps() {
        assertTrue(list.blockReason("com.starfinanz.smob.android.sfinanzstatus")!!.contains("Banking"))
        assertTrue(list.blockReason("com.x8bit.bitwarden")!!.contains("Password manager"))
        assertTrue(list.blockReason("com.google.android.apps.authenticator2")!!.contains("Authenticator"))
        assertTrue(list.blocksSecuritySettings)
        assertNull(list.blockReason("com.spotify.music", "Spotify"))
    }

    @Test
    fun unknownAppsWithSensitiveNamesAreBlocked() {
        assertNotNull(list.blockReason("de.example.regionalbank", "Volksbank Banking"))
        assertNotNull(list.blockReason("org.example.otp", "My Authenticator"))
        assertNotNull(list.blockReason(null, "Sparkasse"))
        assertNull(list.blockReason("com.example.notes", "Notes"))
    }

    @Test
    fun playStoreIsOnlyBlockedInItsPaymentDialogs() {
        assertNull(list.blockReason("com.android.vending", "Google Play Store", "com.android.vending.AssetBrowserActivity"))
        assertNotNull(list.blockReason("com.android.vending", "Google Play Store", "com.google.android.finsky.billing.acquire.PurchaseActivity"))
    }

    @Test
    fun releasingAnAppOverridesListAndNameHeuristic() {
        val released = list.withoutEntry("com.paypal.android.p2pmobile")
        assertNull(released.blockReason("com.paypal.android.p2pmobile", "PayPal"))
        assertTrue("com.paypal.android.p2pmobile" in released.allowed)
        // Adding it back removes the release.
        val again = released.withEntry("com.paypal.android.p2pmobile")
        assertNotNull(again.blockReason("com.paypal.android.p2pmobile", "PayPal"))
        assertFalse("com.paypal.android.p2pmobile" in again.allowed)
    }

    @Test
    fun prefixEntriesAndValidation() {
        val custom = PackageBlocklist(entries = listOf("com.example.*"))
        assertNotNull(custom.blockReason("com.example.shop"))
        assertNull(custom.blockReason("org.other.app"))
        assertEquals("com.example.app", PackageBlocklist.normalize(" com.example.app "))
        assertNull(PackageBlocklist.normalize("not a package"))
        assertNull(PackageBlocklist.normalize("nodots"))
        assertEquals(PackageBlocklist.SECURITY_SETTINGS, PackageBlocklist.normalize("settings:security"))
    }

    @Test
    fun parsingIgnoresCommentsBlankAndInvalidLines() {
        val parsed = PackageBlocklist.parseEntries("# banks\ncom.bank.app\n\nnot valid\ncom.bank.app\nsettings:security # keep")
        assertEquals(listOf("com.bank.app", "settings:security"), parsed)
    }

    @Test
    fun removingSecuritySettingsRuleDisablesIt() {
        assertFalse(list.withoutEntry(PackageBlocklist.SECURITY_SETTINGS).blocksSecuritySettings)
    }
}
