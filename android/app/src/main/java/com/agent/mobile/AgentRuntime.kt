package com.agent.mobile

import android.content.Context
import com.agent.mobile.agent.AgentPlatform
import com.agent.mobile.agent.MemoryStore
import com.agent.mobile.agent.TextFileStore
import com.agent.mobile.channel.TelegramChannelManager
import com.agent.mobile.data.repository.ChatRepository
import com.agent.mobile.data.storage.PreferenceManager
import com.agent.mobile.data.storage.db.AppDatabase
import com.agent.mobile.service.AgentNotifier
import com.agent.mobile.service.scheduler.SchedulerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Process-wide singletons shared by the UI and background workers (scheduled agent tasks,
 * heartbeat). Creating it throws [IllegalStateException] while encrypted storage is locked.
 */
class AgentRuntime private constructor(context: Context) {
    val appContext: Context = context.applicationContext
    val preferenceManager = PreferenceManager(appContext)
    val database: AppDatabase = AppDatabase.getInstance(appContext)
    val chatRepository = ChatRepository(database)
    val memoryStore = MemoryStore(File(appContext.filesDir, "agent/MEMORY.md"))
    /** The user's persona and standing instructions for the agent (like OpenClaw's SOUL.md). */
    val personaStore = TextFileStore(File(appContext.filesDir, "agent/SOUL.md"))
    val scheduler = SchedulerManager(appContext)
    val platform: AgentPlatform = AndroidAgentPlatform(appContext, scheduler)
    /** Phone control layer (screen, notifications, intents, Shizuku) shared by every engine. */
    val phoneController: com.agent.mobile.agent.PhoneController =
        com.agent.mobile.phone.AndroidPhoneController(appContext, preferenceManager) { title, prompt ->
            scheduler.scheduleAgentTask(title, prompt, delayMinutes = 0, repeatEveryMinutes = 0)
        }
    val telegram: TelegramChannelManager by lazy { TelegramChannelManager(this) }
    /** Starts the Termux bridge through RUN_COMMAND when it is not reachable. */
    val bridgeStarter = com.agent.mobile.core.termux.BridgeStarter(appContext)

    companion object {
        @Volatile
        private var instance: AgentRuntime? = null

        fun get(context: Context): AgentRuntime =
            instance ?: synchronized(this) {
                instance ?: AgentRuntime(context).also { instance = it }
            }
    }
}

class AndroidAgentPlatform(
    private val context: Context,
    private val scheduler: SchedulerManager
) : AgentPlatform {
    override fun notifyUser(title: String, message: String) {
        AgentNotifier.notify(context, title, message)
    }

    override fun notifyApprovalNeeded(callId: String, description: String) {
        AgentNotifier.notifyApproval(context, callId, description)
    }

    override fun cancelApprovalNotification(callId: String) {
        AgentNotifier.cancelApproval(context, callId)
    }

    override fun isAppInForeground(): Boolean {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    override fun scheduleAgentTask(title: String, prompt: String, delayMinutes: Long, repeatEveryMinutes: Long): String =
        scheduler.scheduleAgentTask(title, prompt, delayMinutes, repeatEveryMinutes)

    override suspend fun listAgentTasks(): String = withContext(Dispatchers.IO) { scheduler.describeAgentTasks() }

    override suspend fun cancelAgentTask(taskId: String): Boolean = withContext(Dispatchers.IO) { scheduler.cancelAgentTask(taskId) }
}
