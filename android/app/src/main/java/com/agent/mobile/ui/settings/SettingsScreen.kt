package com.agent.mobile.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.agent.AutonomousAgentEngine
import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.data.repository.ChatRepository
import com.agent.mobile.data.storage.PreferenceManager
import com.agent.mobile.service.scheduler.SchedulerManager
import com.agent.mobile.ui.settings.components.CronSyncCard
import com.agent.mobile.ui.settings.components.SecurityCockpitCard
import com.agent.mobile.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    agentEngine: AutonomousAgentEngine,
    preferenceManager: PreferenceManager,
    chatRepository: ChatRepository? = null,
    bridgeClient: TermuxBridgeClient? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val currentConfig by agentEngine.modelConfig.collectAsState()

    var selectedProvider by remember(currentConfig) { mutableStateOf(currentConfig.provider) }
    var apiKey by remember(currentConfig) { mutableStateOf(currentConfig.apiKey) }
    var modelName by remember(currentConfig) { mutableStateOf(currentConfig.modelName) }
    var baseUrl by remember(currentConfig) { mutableStateOf(currentConfig.baseUrl) }

    var fallbackProvider by remember(currentConfig) { mutableStateOf(currentConfig.fallbackProvider) }
    var fallbackModelName by remember(currentConfig) { mutableStateOf(currentConfig.fallbackModelName) }
    var fallbackApiKey by remember(currentConfig) { mutableStateOf(currentConfig.fallbackApiKey) }
    var fallbackBaseUrl by remember(currentConfig) { mutableStateOf(currentConfig.fallbackBaseUrl) }

    var isApiKeyVisible by remember { mutableStateOf(false) }
    var isFallbackApiKeyVisible by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showFallbackSection by remember { mutableStateOf(fallbackProvider != null) }

    val scrollState = rememberScrollState()
    val schedulerManager = remember { SchedulerManager(context) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Einstellungen & Cockpit",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextWhite
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Section 1: Primary Provider Picker
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "PRIMÄRER KI-PROVIDER",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProviderType.values().forEach { provider ->
                        val isSelected = selectedProvider == provider
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) DarkCardElevated else DarkCard,
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) AccentPrimary else BorderSubtle
                            ),
                            onClick = {
                                selectedProvider = provider
                                baseUrl = provider.defaultBaseUrl
                                modelName = provider.defaultModel
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = provider.displayName,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) TextWhite else TextSecondary
                                            )
                                        )
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                color = AccentPrimary.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = "AKTIV",
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = AccentPrimary,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "Standard: ${provider.defaultModel}",
                                        style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, fontSize = 11.sp)
                                    )
                                }

                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        selectedProvider = provider
                                        baseUrl = provider.defaultBaseUrl
                                        modelName = provider.defaultModel
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = AccentPrimary,
                                        unselectedColor = BorderLight
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Section 2: Model & Credentials Configuration Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = DarkCard,
                border = BorderStroke(1.dp, BorderSubtle)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Konfiguration • ${selectedProvider.displayName}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite
                        )
                    )

                    // Suggested Model Quick-Select Chips
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Modell-Vorschläge:",
                            style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            selectedProvider.suggestedModels.forEach { suggestion ->
                                val isChosen = modelName.trim().equals(suggestion, ignoreCase = true)
                                FilterChip(
                                    selected = isChosen,
                                    onClick = { modelName = suggestion },
                                    label = {
                                        Text(
                                            text = suggestion,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentPrimary.copy(alpha = 0.15f),
                                        selectedLabelColor = AccentPrimary,
                                        containerColor = DarkCardElevated,
                                        labelColor = TextSecondary
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isChosen,
                                        borderColor = if (isChosen) AccentPrimary else BorderSubtle,
                                        selectedBorderColor = AccentPrimary,
                                        borderWidth = 1.dp
                                    )
                                )
                            }
                        }
                    }

                    // Model Name Field
                    OutlinedTextField(
                        value = modelName,
                        onValueChange = { modelName = it },
                        label = { Text("Modellname") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = TextWhite),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = BorderSubtle,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            focusedContainerColor = DarkSurface,
                            unfocusedContainerColor = DarkSurface
                        )
                    )

                    // API Key Field
                    if (selectedProvider != ProviderType.LOCAL) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("API Key") },
                            placeholder = { Text("Deinen API-Schlüssel einfügen...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = TextWhite),
                            visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                                        Icon(
                                            imageVector = if (isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Anzeigen",
                                            tint = TextMuted
                                        )
                                    }
                                    IconButton(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                                        if (clip.isNotEmpty()) {
                                            apiKey = clip
                                            Toast.makeText(context, "API Key eingefügt", Toast.LENGTH_SHORT).show()
                                        }
                                    }) {
                                        Icon(Icons.Default.ContentPaste, contentDescription = "Einfügen", tint = AccentPrimary)
                                    }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentPrimary,
                                unfocusedBorderColor = BorderSubtle,
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite,
                                focusedContainerColor = DarkSurface,
                                unfocusedContainerColor = DarkSurface
                            )
                        )
                    }

                    // Advanced Base URL Accordion
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = DarkSurface,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, BorderSubtle),
                        onClick = { showAdvanced = !showAdvanced }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Erweiterte Endpunkt-Einstellungen", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary))
                            }
                            Icon(if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = TextMuted)
                        }
                    }

                    AnimatedVisibility(visible = showAdvanced) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Base URL") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = TextWhite),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentPrimary,
                                unfocusedBorderColor = BorderSubtle,
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite,
                                focusedContainerColor = DarkSurface,
                                unfocusedContainerColor = DarkSurface
                            )
                        )
                    }
                }
            }

            // Section 3: Fallback Provider Resilience Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = DarkCard,
                border = BorderStroke(1.dp, BorderSubtle)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Sekundärer Fallback-Provider",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, color = TextWhite)
                            )
                            Text(
                                text = "Automatischer Wechsel bei 429 Rate Limit, Timeout oder API-Ausfall",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp)
                            )
                        }
                        Switch(
                            checked = showFallbackSection,
                            onCheckedChange = {
                                showFallbackSection = it
                                if (!it) fallbackProvider = null
                                else if (fallbackProvider == null) {
                                    fallbackProvider = if (selectedProvider != ProviderType.GROQ) ProviderType.GROQ else ProviderType.LOCAL
                                    fallbackModelName = fallbackProvider!!.defaultModel
                                    fallbackBaseUrl = fallbackProvider!!.defaultBaseUrl
                                }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = AccentPrimary)
                        )
                    }

                    AnimatedVisibility(visible = showFallbackSection && fallbackProvider != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Fallback-Provider auswählen:", style = MaterialTheme.typography.labelSmall.copy(color = TextMuted))
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ProviderType.values().filter { it != selectedProvider }.forEach { prov ->
                                    val isChosen = fallbackProvider == prov
                                    FilterChip(
                                        selected = isChosen,
                                        onClick = {
                                            fallbackProvider = prov
                                            fallbackModelName = prov.defaultModel
                                            fallbackBaseUrl = prov.defaultBaseUrl
                                        },
                                        label = { Text(prov.displayName, fontSize = 11.sp) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = AccentPrimary.copy(alpha = 0.2f),
                                            selectedLabelColor = AccentPrimary
                                        )
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = fallbackModelName,
                                onValueChange = { fallbackModelName = it },
                                label = { Text("Fallback-Modell") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = TextWhite),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentPrimary,
                                    unfocusedBorderColor = BorderSubtle,
                                    focusedTextColor = TextWhite,
                                    unfocusedTextColor = TextWhite,
                                    focusedContainerColor = DarkSurface,
                                    unfocusedContainerColor = DarkSurface
                                )
                            )

                            if (fallbackProvider != ProviderType.LOCAL) {
                                OutlinedTextField(
                                    value = fallbackApiKey,
                                    onValueChange = { fallbackApiKey = it },
                                    label = { Text("Fallback API Key") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(8.dp),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = TextWhite),
                                    visualTransformation = if (isFallbackApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { isFallbackApiKeyVisible = !isFallbackApiKeyVisible }) {
                                            Icon(if (isFallbackApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null, tint = TextMuted)
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = AccentPrimary,
                                        unfocusedBorderColor = BorderSubtle,
                                        focusedTextColor = TextWhite,
                                        unfocusedTextColor = TextWhite,
                                        focusedContainerColor = DarkSurface,
                                        unfocusedContainerColor = DarkSurface
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Section 4: Security Cockpit & Guardrails
            SecurityCockpitCard(
                agentEngine = agentEngine,
                preferenceManager = preferenceManager,
                chatRepository = chatRepository
            )

            // Section 5: Cron-Sync & Hybrid Scheduler
            if (bridgeClient != null) {
                CronSyncCard(
                    bridgeClient = bridgeClient,
                    schedulerManager = schedulerManager
                )
            }

            // Section 6: Save Button
            Button(
                onClick = {
                    val finalModel = modelName.trim().ifEmpty { selectedProvider.defaultModel }
                    val newConfig = ModelConfig(
                        provider = selectedProvider,
                        modelName = finalModel,
                        apiKey = apiKey.trim(),
                        baseUrl = baseUrl.trim().ifEmpty { selectedProvider.defaultBaseUrl },
                        fallbackProvider = if (showFallbackSection) fallbackProvider else null,
                        fallbackModelName = if (showFallbackSection) fallbackModelName.trim() else "",
                        fallbackApiKey = if (showFallbackSection) fallbackApiKey.trim() else "",
                        fallbackBaseUrl = if (showFallbackSection) fallbackBaseUrl.trim() else ""
                    )
                    agentEngine.setModelConfig(newConfig)
                    preferenceManager.saveModelConfig(newConfig)
                    focusManager.clearFocus()
                    Toast.makeText(context, "✅ Gespeichert: ${selectedProvider.displayName} (${finalModel})", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentPrimary,
                    contentColor = Color.Black
                )
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Alle Einstellungen speichern",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}
