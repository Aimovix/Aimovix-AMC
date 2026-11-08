package com.agent.mobile.channel

import com.agent.mobile.AgentRuntime
import com.agent.mobile.agent.AutonomousAgentEngine
import com.agent.mobile.agent.RunMode
import com.agent.mobile.data.model.ConnectionStatus
import com.agent.mobile.data.network.TermuxBridgeClient

/**
 * [ChannelAgent] backed by a headless [AutonomousAgentEngine] with its own Termux bridge
 * connection, so channel conversations never interfere with the chat in the app.
 */
class EngineChannelAgent(
    private val runtime: AgentRuntime,
    private val channelName: String
) : ChannelAgent {

    private val prefs = runtime.preferenceManager
    private val bridge = TermuxBridgeClient(token = prefs.loadAuthToken()).also { it.attachContext(runtime.appContext) }
    private val engine = AutonomousAgentEngine(
        bridgeClient = bridge,
        preferenceManager = prefs,
        chatRepository = runtime.chatRepository,
        memoryStore = runtime.memoryStore,
        platform = runtime.platform,
        headless = true,
        personaStore = runtime.personaStore
    ).also { it.channelName = channelName }

    override val isBusy: Boolean get() = engine.isBusy.value

    override suspend fun run(request: TurnRequest): AutonomousAgentEngine.UnattendedResult {
        // Settings may have changed in the app since the last message.
        engine.reloadSettings()
        engine.approvalHandler = request.approval
        engine.attachmentSink = request.attachments
        if (bridge.connectionStatus.value !is ConnectionStatus.Connected) {
            val token = prefs.loadAuthToken()
            bridge.setToken(token)
            bridge.connect(token)
            // The agent can still answer without Termux (web, memory), so don't fail here.
            bridge.awaitConnected(10_000L)
        }
        return engine.runHeadless(
            title = request.title,
            prompt = request.text,
            mode = RunMode.REMOTE,
            sessionId = request.sessionId,
            imageBase64 = request.imageBase64,
            imageMimeType = request.imageMimeType
        )
    }

    override fun steer(text: String): Boolean = engine.queueSteering(text)

    override fun statusLine(): String {
        val config = engine.modelConfig.value
        val bridgeState = when (bridge.connectionStatus.value) {
            is ConnectionStatus.Connected -> "connected"
            is ConnectionStatus.Connecting -> "connecting"
            else -> "offline (it connects when a task starts)"
        }
        return "Model: ${config.provider.displayName} · ${config.modelName}\n" +
            "Security mode: ${engine.securityPreset.value.title}\n" +
            "Termux bridge: $bridgeState"
    }

    override fun close() {
        bridge.disconnect()
        engine.close()
    }
}
