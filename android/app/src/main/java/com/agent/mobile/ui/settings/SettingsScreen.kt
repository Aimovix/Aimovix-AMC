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
    onOpenTutorial: (() -> Unit)? = null,
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

    val switchPrimaryProvider = { target: ProviderType ->
        if (selectedProvider != target) {
            preferenceManager.saveProviderProfile(selectedProvider, apiKey.trim(), baseUrl.trim(), modelName.trim())
            selectedProvider = target
            val profile = preferenceManager.loadProviderProfile(target)
            baseUrl = profile.baseUrl
            modelName = profile.modelName
            apiKey = profile.apiKey
        }
    }

    val switchFallbackProvider = { target: ProviderType ->
        if (fallbackProvider != target) {
            fallbackProvider?.let { curr ->
                preferenceManager.saveProviderProfile(curr, fallbackApiKey.trim(), fallbackBaseUrl.trim(), fallbackModelName.trim())
            }
            fallbackProvider = target
            val profile = preferenceManager.loadProviderProfile(target)
            fallbackBaseUrl = profile.baseUrl
            fallbackModelName = profile.modelName
            fallbackApiKey = profile.apiKey
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = AccentPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Provider Selection Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = DarkCard,
                border = BorderStroke(1.dp, BorderSubtle)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "AI MODEL PROVIDER",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 1.sp
                        )
                    )

                    ProviderType.entries.forEach { provider ->
                        val isSelected = selectedProvider == provider
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) DarkCardElevated else DarkCard,
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) AccentPrimary else BorderSubtle
                            ),
                            onClick = { switchPrimaryProvider(provider) }
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
                                                    text = "ACTIVE",
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
                                        text = "Default: ${provider.defaultModel}",
                                        style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, fontSize = 11.sp)
                                    )
                                }

                                RadioButton(
                                    selected = isSelected,
                                    onClick = { switchPrimaryProvider(provider) },
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
                        text = "Configuration • ${selectedProvider.displayName}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite
                        )
                    )

                    // Suggested Model Quick-Select Chips
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Suggested models:",
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
                        label = { Text("Model name") },
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
                            placeholder = { Text("Paste your API key...") },
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
                                            contentDescription = "Show",
                                            tint = TextMuted
                                        )
                                    }
                                    IconButton(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                                        if (clip.isNotEmpty()) {
                                            apiKey = clip
                                            Toast.makeText(context, "API key pasted", Toast.LENGTH_SHORT).show()
                                        }
                                    }) {
                                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = AccentPrimary)
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
                                Text("Advanced endpoint settings", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary))
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
                                text = "Secondary fallback provider",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, color = TextWhite)
                            )
                            Text(
                                text = "Switch automatically on rate limits (429), timeouts, or API failures",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
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
                            Text("Select fallback provider:", style = MaterialTheme.typography.labelSmall.copy(color = TextMuted))
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ProviderType.entries.filter { it != selectedProvider }.forEach { prov ->
                                    val isChosen = fallbackProvider == prov
                                    FilterChip(
                                        selected = isChosen,
                                        onClick = { switchFallbackProvider(prov) },
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
                                label = { Text("Fallback model") },
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

            // Section 6: App Walkthrough & Tutorial
            if (onOpenTutorial != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = DarkCard,
                    border = BorderStroke(1.dp, BorderSubtle)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "App Tutorial & Walkthrough",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextWhite
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Replay the step-by-step setup guide and app tour",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.5.sp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = onOpenTutorial,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentPrimary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                        }
                    }
                }
            }

            // Section 7: Save Button
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
                    if (showFallbackSection && fallbackProvider != null) {
                        preferenceManager.saveProviderProfile(
                            fallbackProvider!!,
                            fallbackApiKey.trim(),
                            fallbackBaseUrl.trim().ifEmpty { fallbackProvider!!.defaultBaseUrl },
                            fallbackModelName.trim().ifEmpty { fallbackProvider!!.defaultModel }
                        )
                    }
                    focusManager.clearFocus()
                    Toast.makeText(context, "✅ Saved: ${selectedProvider.displayName} (${finalModel})", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentPrimary,
                    contentColor = Color.Black
                )
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Save all settings",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                )
            }
        }
    }
}
