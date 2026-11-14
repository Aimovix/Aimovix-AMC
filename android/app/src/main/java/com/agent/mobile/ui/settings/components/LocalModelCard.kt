package com.agent.mobile.ui.settings.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.agent.mobile.agent.AutonomousAgentEngine
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.data.storage.PreferenceManager
import com.agent.mobile.local.DeviceProfiler
import com.agent.mobile.local.LocalModelCatalog
import com.agent.mobile.local.LocalModelSetup
import com.agent.mobile.local.LocalSetupState
import com.agent.mobile.local.Recommendation
import com.agent.mobile.local.SetupError
import com.agent.mobile.local.SetupPhase
import com.agent.mobile.local.SetupProgress
import com.agent.mobile.service.scheduler.LocalModelSetupWorker
import com.agent.mobile.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Short name of a phase for the progress line. */
fun setupPhaseLabel(phase: SetupPhase): String = when (phase) {
    SetupPhase.IDLE -> "Not set up"
    SetupPhase.CHECK -> "Checking"
    SetupPhase.DOWNLOAD -> "Downloading"
    SetupPhase.VERIFY -> "Verifying"
    SetupPhase.START -> "Starting"
    SetupPhase.BENCHMARK -> "Testing"
    SetupPhase.DONE -> "Active"
    SetupPhase.FAILED -> "Stopped"
    SetupPhase.CANCELLED -> "Cancelled"
}

/** What the card shows, from the worker (live) and the saved state (after the app was closed). */
sealed class LocalCardView {
    data class Running(val progress: SetupProgress) : LocalCardView()
    /** Enqueued: waiting for network or for a retry after Termux was unreachable. */
    object Waiting : LocalCardView()
    data class Idle(val state: LocalSetupState) : LocalCardView()
}

fun localCardView(info: WorkInfo.State?, live: SetupProgress?, saved: LocalSetupState): LocalCardView = when (info) {
    WorkInfo.State.RUNNING -> LocalCardView.Running(live ?: SetupProgress(saved.phase.takeIf { saved.active } ?: SetupPhase.CHECK, "Preparing…"))
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> LocalCardView.Waiting
    else -> LocalCardView.Idle(saved)
}

/** Label of the main button when no setup runs. */
fun localSetupButton(state: LocalSetupState): String = when {
    state.phase == SetupPhase.DONE -> "Set up again"
    state.resumable -> "Resume setup"
    else -> "Set up local AI"
}

/**
 * Local AI on the phone. One tap starts [LocalModelSetupWorker]: it picks the model for this
 * phone, downloads it in Termux, starts llama-server, tests it and makes it the active model
 * with the current cloud model as fallback. The setup runs in the background; this card only
 * shows its progress and survives leaving the screen or closing the app.
 */
@Composable
fun LocalModelCard(
    agentEngine: AutonomousAgentEngine,
    preferenceManager: PreferenceManager,
    bridgeClient: TermuxBridgeClient,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val setup = remember { LocalModelSetup(context.applicationContext, bridgeClient) }
    val store = remember { LocalModelSetupWorker.store(context.applicationContext) }
    var advice by remember { mutableStateOf<String?>(null) }
    val infos by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(LocalModelSetupWorker.UNIQUE_NAME) }
        .collectAsState(initial = emptyList())
    val info = infos.lastOrNull()
    // Re-read the saved state whenever the worker changes state (and after the app was reopened).
    val saved = remember(info?.state, info?.progress) { store.load() }
    val view = localCardView(info?.state, info?.progress?.let { LocalModelSetupWorker.fromData(it) }, saved)

    LaunchedEffect(saved.phase) {
        if (saved.phase == SetupPhase.DONE) agentEngine.reloadSettings()
    }
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

            when (view) {
                is LocalCardView.Running -> {
                    val p = view.progress
                    Text(
                        "${setupPhaseLabel(p.phase)}${if (p.modelTitle.isNotBlank()) " · ${p.modelTitle}" else ""}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    val fraction = p.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = AccentPrimary)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AccentPrimary)
                    }
                    val detail = LocalModelSetupWorker.detailLine(p)
                    Text(
                        listOf(p.message, detail).filter { it.isNotBlank() }.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "This runs in the background. You can leave this screen or the app.",
                        style = MaterialTheme.typography.bodySmall, color = TextMuted
                    )
                    OutlinedButton(onClick = { LocalModelSetupWorker.cancel(context) }) { Text("Cancel") }
                }
                LocalCardView.Waiting -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = TextMuted)
                    Text(
                        if (saved.error != null) "${saved.message}\nAMC tries again automatically when the network and Termux are available."
                        else "Waiting for a network connection…",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = { LocalModelSetupWorker.cancel(context) }) { Text("Cancel") }
                }
                is LocalCardView.Idle -> {
                    val state = view.state
                    if (state.message.isNotBlank()) {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.phase == SetupPhase.FAILED) RedEmergency else TextWhite
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val error = state.error
                        if (state.phase == SetupPhase.FAILED && error != null && error.fixLabel != null) {
                            Button(onClick = { fixSetupError(context, error) }) { Text(error.fixLabel) }
                            // Fixes done outside AMC (storage, Termux update) need a way to start again.
                            if (error == SetupError.STORAGE || error == SetupError.BRIDGE_OLD) {
                                OutlinedButton(onClick = { LocalModelSetupWorker.start(context) }) { Text("Try again") }
                            }
                        } else {
                            Button(
                                enabled = state.phase != SetupPhase.FAILED || error?.fixLabel == null,
                                onClick = { LocalModelSetupWorker.start(context) }
                            ) { Text(localSetupButton(state)) }
                        }
                    }
                }
            }
        }
    }
}

/** The one fix each error offers. */
private fun fixSetupError(context: Context, error: SetupError) {
    when (error) {
        SetupError.STORAGE -> {
            val opened = runCatching {
                context.startActivity(Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (!opened) Toast.makeText(context, "Open Android settings → Storage", Toast.LENGTH_LONG).show()
        }
        SetupError.BRIDGE_OLD -> {
            // Runs the installer in Termux (RUN_COMMAND); without that permission the command is copied.
            val started = com.agent.mobile.core.termux.TermuxRunCommand.start(
                context, LocalModelSetup.UPDATE_COMMAND, "AMC: update bridge", background = false
            )
            if (started) {
                Toast.makeText(context, "The installer runs in Termux. When it says 'Setup complete', tap Try again.", Toast.LENGTH_LONG).show()
            } else {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("AMC", LocalModelSetup.UPDATE_COMMAND))
                Toast.makeText(context, "Update command copied. Paste it in Termux, then tap Try again.", Toast.LENGTH_LONG).show()
            }
        }
        else -> LocalModelSetupWorker.start(context)
    }
}
