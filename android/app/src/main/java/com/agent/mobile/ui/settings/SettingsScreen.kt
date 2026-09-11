package com.agent.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.agent.AutonomousAgentEngine
import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType
import com.agent.mobile.ui.theme.DarkBackground
import com.agent.mobile.ui.theme.DarkCard
import com.agent.mobile.ui.theme.GreenPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    agentEngine: AutonomousAgentEngine,
    modifier: Modifier = Modifier
) {
    val currentConfig by agentEngine.modelConfig.collectAsState()

    var selectedProvider by remember(currentConfig) { mutableStateOf(currentConfig.provider) }
    var apiKey by remember(currentConfig) { mutableStateOf(currentConfig.apiKey) }
    var modelName by remember(currentConfig) { mutableStateOf(currentConfig.modelName) }
    var baseUrl by remember(currentConfig) { mutableStateOf(currentConfig.baseUrl) }

    val scrollState = rememberScrollState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = { Text("KI- & Agenten-Einstellungen", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            // Provider Selection
            Text("KI-Anbieter auswählen", style = MaterialTheme.typography.titleLarge.copy(fontSize = 15.sp))
            Spacer(modifier = Modifier.height(10.dp))

            ProviderType.values().forEach { provider ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedProvider == provider) GreenPrimary.copy(alpha = 0.15f) else DarkCard
                    ),
                    shape = RoundedCornerShape(8.dp),
                    onClick = {
                        selectedProvider = provider
                        baseUrl = provider.defaultBaseUrl
                        modelName = provider.defaultModel
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedProvider == provider,
                            onClick = {
                                selectedProvider = provider
                                baseUrl = provider.defaultBaseUrl
                                modelName = provider.defaultModel
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = GreenPrimary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = provider.displayName,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selectedProvider == provider) GreenPrimary else Color.White
                                )
                            )
                            Text(
                                text = "Standard-Modell: ${provider.defaultModel}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Configuration for selected provider
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Konfiguration: ${selectedProvider.displayName}",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 15.sp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (selectedProvider != ProviderType.LOCAL) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("API Key") },
                            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = GreenPrimary) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = GreenPrimary,
                                unfocusedBorderColor = Color.Gray,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    OutlinedTextField(
                        value = modelName,
                        onValueChange = { modelName = it },
                        label = { Text("Modell-Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GreenPrimary,
                            unfocusedBorderColor = Color.Gray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL / Endpoint") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GreenPrimary,
                            unfocusedBorderColor = Color.Gray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Save Button
            Button(
                onClick = {
                    val newConfig = ModelConfig(
                        provider = selectedProvider,
                        apiKey = apiKey.trim(),
                        modelName = modelName.trim(),
                        baseUrl = baseUrl.trim()
                    )
                    agentEngine.setModelConfig(newConfig)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Einstellungen speichern", fontWeight = FontWeight.Bold)
            }
        }
    }
}
