package com.agent.mobile.ui.settings.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.agent.mobile.agent.AutonomousAgentEngine
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.data.storage.PreferenceManager
import com.agent.mobile.local.DeviceProfiler
import com.agent.mobile.local.LocalModelCatalog
import com.agent.mobile.local.LocalModelSetup
import com.agent.mobile.local.Recommendation
import com.agent.mobile.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local AI on the phone. "Smart Setup" detects the phone, picks a model from the catalog,
 * downloads and verifies it in Termux, starts llama-server, benchmarks it and makes it the
 * active model with the current cloud model as fallback. Manual setup stays in the model
 * settings above (provider "Local server").
 */
@Composable
fun LocalModelCard(
    agentEngine: AutonomousAgentEngine,
    preferenceManager: PreferenceManager,
    bridgeClient: TermuxBridgeClient,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setup = remember { LocalModelSetup(context.applicationContext, bridgeClient) }
    var advice by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        advice = withContext(Dispatchers.IO) {
            val profile = DeviceProfiler.profile(context)
            when (val r = LocalModelCatalog.recommend(profile, setup.catalog())) {
                is Recommendation.Model -> "Recommended for this phone (${profile.totalRamMb / 1024} GB RAM): ${r.model.title}, " +
                    "context ${r.ctx / 1024}k, ${r.threads} threads." + r.notes.joinToString("") { " $it" }
                is Recommendation.NotRecommended -> r.reason
            }
        }
    }

    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = DarkCard) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "Local AI (on this phone)", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Runs a small model with llama.cpp in Termux. Private and offline, but slower and weaker than " +
                    "cloud models; the current cloud model stays as fallback.",
                style = MaterialTheme.typography.bodySmall
            )
            advice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted) }
            progress?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Button(
                enabled = !running,
                onClick = {
                    running = true
                    scope.launch {
                        val outcome = setup.run { step -> progress = step }
                        progress = when (outcome) {
                            is LocalModelSetup.Outcome.Ready -> {
                                val config = LocalModelCatalog.localConfig(agentEngine.modelConfig.value, LocalModelSetup.PORT)
                                agentEngine.setModelConfig(config)
                                preferenceManager.saveModelConfig(config)
                                "Active: ${outcome.model.title} (%.1f tokens/s). Cloud fallback: ${config.fallbackProvider?.displayName ?: "none"}."
                                    .format(outcome.tokensPerSecond)
                            }
                            is LocalModelSetup.Outcome.Failed -> outcome.reason
                        }
                        running = false
                    }
                }
            ) {
                Text(if (running) "Setting up…" else "Smart Setup")
            }
        }
    }
}
