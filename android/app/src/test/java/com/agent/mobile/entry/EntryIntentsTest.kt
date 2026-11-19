package com.agent.mobile.entry

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.agent.mobile.MainActivity
import com.agent.mobile.MainViewModel
import com.agent.mobile.R
import com.agent.mobile.agent.RunMode
import com.agent.mobile.agent.RunRegistry
import com.agent.mobile.agent.RunTrigger
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.xmlpull.v1.XmlPullParser

/** Teil E, E2/E3: share to AMC, launcher shortcuts and the Quick Settings tile. */
@RunWith(RobolectricTestRunner::class)
class EntryIntentsTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val stopped = mutableListOf<String>()

    @After
    fun tearDown() {
        RunRegistry.active.value.forEach { RunRegistry.unregister(it.runId) }
    }

    private fun fakeRun(id: String) = RunRegistry.register(
        RunRegistry.ActiveRun(id, "Backup", RunMode.UNATTENDED, RunTrigger.SCHEDULE, "s-$id", unattended = true)
    ) { stopped += id }

    private fun share(text: String? = null, subject: String? = null, image: Uri? = null, type: String = "text/plain") =
        Intent(Intent.ACTION_SEND).apply {
            this.type = type
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            image?.let { putExtra(Intent.EXTRA_STREAM, it) }
        }

    @Test
    fun sharedContentBecomesADraft() {
        assertEquals(ChatDraft("https://example.org/a"), EntryIntents.draftFromShare(share("https://example.org/a")))
        assertEquals(ChatDraft("Release notes\nhttps://example.org/r"), EntryIntents.draftFromShare(share("https://example.org/r", subject = "Release notes")))
        val photo = Uri.parse("content://media/external/images/1")
        assertEquals(ChatDraft("", photo), EntryIntents.draftFromShare(share(image = photo, type = "image/jpeg")))
        assertNull("Nothing to share", EntryIntents.draftFromShare(share()))
        assertNull("Not a share", EntryIntents.draftFromShare(Intent(Intent.ACTION_VIEW)))
        val long = "x".repeat(EntryIntents.MAX_SHARED_CHARS + 50)
        assertEquals(EntryIntents.MAX_SHARED_CHARS, EntryIntents.draftFromShare(share(long))!!.text.length)
    }

    @Test
    fun aShareOpensTheRunningChatWithTheDraftAndNeverSends() {
        val relay = Robolectric.buildActivity(ShareRelayActivity::class.java, share("Summarize this", subject = "Article")).setup().get()
        val next = shadowOf(relay).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component?.className)
        assertTrue(next.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(next.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(relay.isFinishing)

        val vm = MainViewModel()
        vm.handleEntry(next)
        assertEquals(ChatDraft("Article\nSummarize this"), vm.pendingDraft.value)
        assertEquals(1L, vm.openChatRequests.value)
        assertEquals(0L, vm.newTaskRequests.value)
    }

    @Test
    fun theExportedShareEntryCannotStopRuns() {
        fakeRun("r1")
        val relay = Robolectric.buildActivity(ShareRelayActivity::class.java, Intent(EntryIntents.ACTION_STOP_ALL)).setup().get()
        assertTrue(stopped.isEmpty())
        assertNull(shadowOf(relay).nextStartedActivity)
    }

    @Test
    fun shortcutsStartANewTaskOrStopEveryRun() {
        fakeRun("r1")
        fakeRun("r2")
        Robolectric.buildActivity(ShortcutRelayActivity::class.java, Intent(EntryIntents.ACTION_STOP_ALL)).setup()
        assertEquals(listOf("r1", "r2"), stopped.sorted())

        val relay = Robolectric.buildActivity(ShortcutRelayActivity::class.java, Intent(EntryIntents.ACTION_NEW_TASK)).setup().get()
        val next = shadowOf(relay).nextStartedActivity
        val vm = MainViewModel()
        vm.handleEntry(next)
        assertEquals(1L, vm.newTaskRequests.value)
        assertEquals(1L, vm.openChatRequests.value)
        assertNull(vm.pendingDraft.value)
    }

    @Test
    fun staticShortcutsPointAtTheShortcutRelay() {
        val parser = app.resources.getXml(R.xml.shortcuts)
        val targets = mutableListOf<Pair<String, String>>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "intent") {
                val ns = "http://schemas.android.com/apk/res/android"
                targets += parser.getAttributeValue(ns, "action") to parser.getAttributeValue(ns, "targetClass")
            }
        }
        assertEquals(
            listOf(
                EntryIntents.ACTION_NEW_TASK to ShortcutRelayActivity::class.java.name,
                EntryIntents.ACTION_STOP_ALL to ShortcutRelayActivity::class.java.name
            ),
            targets
        )
    }

    @Test
    fun theQuickSettingsTileStopsEveryRun() {
        fakeRun("t1")
        val tile = Robolectric.buildService(StopAgentsTileService::class.java).create().get()
        tile.onStartListening()
        tile.onClick()
        assertEquals(listOf("t1"), stopped)
    }
}
