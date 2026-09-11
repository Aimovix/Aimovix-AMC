package com.agent.mobile.ui.chat.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.agent.mobile.ui.theme.GreenPrimary
import com.agent.mobile.ui.theme.RedEmergency
import java.util.Locale

@Composable
fun VoiceInputButton(
    onSpeechResult: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isListening by remember { mutableStateOf(false) }

    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startListening(context, speechRecognizer, onListeningChange = { isListening = it }, onResult = onSpeechResult)
        } else {
            Toast.makeText(context, "Mikrofon-Berechtigung erforderlich", Toast.LENGTH_SHORT).show()
        }
    }

    val micColor by animateColorAsState(
        targetValue = if (isListening) RedEmergency else GreenPrimary,
        label = "mic_color"
    )

    IconButton(
        onClick = {
            if (speechRecognizer == null) {
                Toast.makeText(context, "Spracherkennung auf diesem Gerät nicht verfügbar", Toast.LENGTH_SHORT).show()
                return@IconButton
            }

            if (isListening) {
                speechRecognizer.stopListening()
                isListening = false
            } else {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (hasPermission) {
                    startListening(context, speechRecognizer, onListeningChange = { isListening = it }, onResult = onSpeechResult)
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        },
        modifier = modifier
    ) {
        Icon(
            imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
            contentDescription = "Spracheingabe",
            tint = micColor,
            modifier = Modifier.size(22.dp)
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer?.destroy()
        }
    }
}

private fun startListening(
    context: Context,
    speechRecognizer: SpeechRecognizer?,
    onListeningChange: (Boolean) -> Unit,
    onResult: (String) -> Unit
) {
    if (speechRecognizer == null) return

    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    speechRecognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onListeningChange(true)
            Toast.makeText(context, "🎤 Sprich jetzt...", Toast.LENGTH_SHORT).show()
        }

        override fun onResults(results: Bundle?) {
            onListeningChange(false)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val spokenText = matches?.firstOrNull() ?: ""
            if (spokenText.isNotBlank()) {
                onResult(spokenText)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull() ?: ""
            if (partial.isNotBlank()) {
                onResult(partial)
            }
        }

        override fun onError(error: Int) {
            onListeningChange(false)
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            onListeningChange(false)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })

    speechRecognizer.startListening(intent)
}
