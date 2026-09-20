package com.agent.mobile.ui.tutorial

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.agent.mobile.ui.setup.ApkDownloadHelper
import com.agent.mobile.ui.theme.*

@Composable
fun OnboardingTutorialDialog(
    onDismiss: () -> Unit,
    onNavigateToSetup: () -> Unit,
    onNavigateToChat: () -> Unit
) {
    var currentStep by remember { mutableIntStateOf(0) }
    val totalSteps = 5
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = DarkBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                // Top Navigation Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AccentPrimary.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "TUTORIAL",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = AccentPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    letterSpacing = 1.sp
                                )
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Step ${currentStep + 1} of $totalSteps",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextMuted,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }

                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = TextMuted)
                    ) {
                        Text("Skip", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                HorizontalDivider(color = BorderSubtle, thickness = 1.dp)

                // Scrollable Content Area with animation between steps
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    AnimatedContent(
                        targetState = currentStep,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "TutorialStepAnimation"
                    ) { step ->
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            when (step) {
                                0 -> StepOverview()
                                1 -> StepTermuxSetup(context)
                                2 -> StepAiProviders()
                                3 -> StepSecurityAndSafety()
                                4 -> StepControlsAndStart(
                                    onNavigateToSetup = {
                                        onDismiss()
                                        onNavigateToSetup()
                                    },
                                    onNavigateToChat = {
                                        onDismiss()
                                        onNavigateToChat()
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = BorderSubtle, thickness = 1.dp)

                // Bottom Action Bar with Stepper & Next/Back buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentStep > 0) {
                        OutlinedButton(
                            onClick = { currentStep-- },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, BorderSubtle),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Back", fontSize = 13.sp)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(80.dp))
                    }

                    // Progress Indicator Dots
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (i in 0 until totalSteps) {
                            val isActive = i == currentStep
                            Box(
                                modifier = Modifier
                                    .size(if (isActive) 8.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(if (isActive) AccentPrimary else TextMuted.copy(alpha = 0.35f))
                            )
                        }
                    }

                    if (currentStep < totalSteps - 1) {
                        Button(
                            onClick = { currentStep++ },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentPrimary,
                                contentColor = Color.Black
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Next", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    } else {
                        Button(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentPrimary,
                                contentColor = Color.Black
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Done", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepOverview() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SmartToy, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "Welcome to AMC",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                )
                Text(
                    text = "Autonomous AI Agent on Android",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = DarkCard,
            border = BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "What is AMC?",
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    fontSize = 14.sp
                )
                Text(
                    text = "Unlike standard chatbots that only talk, AMC is an autonomous agent capable of executing real tasks on your Android device. It uses an AI model of your choice to plan, run tools, manipulate files, and inspect device state in real time.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }

        Text(
            text = "KEY CAPABILITIES",
            style = MaterialTheme.typography.labelSmall.copy(
                color = TextMuted,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        )

        FeatureHighlightCard(
            icon = Icons.Default.Terminal,
            title = "Real Command Execution",
            description = "AMC safely executes shell commands, Python scripts, and mobile utilities inside a local Linux sandbox (Termux)."
        )

        FeatureHighlightCard(
            icon = Icons.Default.Psychology,
            title = "Autonomous Multi-Step Planning",
            description = "Give AMC high-level goals. It breaks them down, runs commands, analyzes errors, and iterates until the goal is achieved."
        )

        FeatureHighlightCard(
            icon = Icons.Default.Shield,
            title = "Security & Full Supervision",
            description = "You choose how much autonomy the agent has. Supervise every action or enable Turbo mode for fast execution."
        )
    }
}

@Composable
private fun StepTermuxSetup(context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Terminal, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "Connect Termux Engine",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                )
                Text(
                    text = "The Linux execution backend",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )
            }
        }

        Text(
            text = "Android isolates apps for security. AMC connects to Termux via a secure local bridge to execute commands safely in Linux. Follow these 3 simple steps:",
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )

        // Substep 1: Direct APK Downloads
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = DarkCard,
            border = BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BadgeNumber(number = "1")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Download & Install Apps", fontWeight = FontWeight.Bold, color = TextWhite, fontSize = 13.5.sp)
                }
                Text(
                    text = "Tap below to directly download official APKs. Open the downloads to install both apps:",
                    color = TextSecondary,
                    fontSize = 12.5.sp
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { ApkDownloadHelper.downloadTermux(context) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkCardElevated, contentColor = TextWhite),
                        border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp), tint = AccentPrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Termux APK", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { ApkDownloadHelper.downloadTermuxApi(context) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkCardElevated, contentColor = TextWhite),
                        border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp), tint = AccentPrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Termux:API APK", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Substep 2: 1-Click Setup Command
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = DarkCard,
            border = BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BadgeNumber(number = "2")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Run Setup Script in Termux", fontWeight = FontWeight.Bold, color = TextWhite, fontSize = 13.5.sp)
                }
                Text(
                    text = "Open Termux, paste this command, and press Enter:",
                    color = TextSecondary,
                    fontSize = 12.5.sp
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = TerminalBg,
                    border = BorderStroke(1.dp, BorderSubtle)
                ) {
                    Text(
                        text = "curl -sL https://raw.githubusercontent.com/Aimovix/Aimovix-AMC/main/termux-bridge/setup.sh | bash",
                        color = TerminalGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(
                            "AMC Setup",
                            "curl -sL https://raw.githubusercontent.com/Aimovix/Aimovix-AMC/main/termux-bridge/setup.sh | bash"
                        )
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Setup command copied! Paste it in Termux.", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Setup Command", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                }
            }
        }

        // Substep 3: Pairing Token
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = DarkCard,
            border = BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BadgeNumber(number = "3")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pair with Auth Token", fontWeight = FontWeight.Bold, color = TextWhite, fontSize = 13.5.sp)
                }
                Text(
                    text = "Type 'amc token' in Termux to display your secret token, then paste it in AMC's Setup tab to connect.",
                    color = TextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

@Composable
private fun StepAiProviders() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudQueue, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "Select Your AI Model",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                )
                Text(
                    text = "Configure your preferred AI provider",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )
            }
        }

        Text(
            text = "AMC is completely provider-agnostic. Bring your own API key or run completely offline with local models:",
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )

        FeatureHighlightCard(
            icon = Icons.Default.AutoAwesome,
            title = "Google Gemini (Recommended)",
            description = "Fast, affordable, and high token limits. Free API keys can be generated at Google AI Studio."
        )

        FeatureHighlightCard(
            icon = Icons.Default.Code,
            title = "Anthropic Claude & OpenAI",
            description = "Outstanding reasoning and coding capabilities for complex automation workflows."
        )

        FeatureHighlightCard(
            icon = Icons.Default.Bolt,
            title = "Groq (Ultra-Fast)",
            description = "Lightning-fast inference speeds with open models like Llama 3."
        )

        FeatureHighlightCard(
            icon = Icons.Default.Memory,
            title = "Local Models (Offline)",
            description = "Run models completely private and offline via llama-server or Ollama on your device."
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = DarkCardElevated,
            border = BorderStroke(1.dp, BorderSubtle)
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SwapCalls, contentDescription = null, tint = AccentSecondary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("Automatic Fallback Switching", fontWeight = FontWeight.Bold, color = TextWhite, fontSize = 12.5.sp)
                    Text(
                        "Configure a secondary fallback provider in Settings. If your primary model hits a rate limit (HTTP 429), AMC switches automatically.",
                        color = TextSecondary,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun StepSecurityAndSafety() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Security, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "Security & Safety Presets",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                )
                Text(
                    text = "Full control over agent autonomy",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )
            }
        }

        Text(
            text = "You decide how much control AMC has over your device. You can switch presets anytime directly from the top bar in Chat:",
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )

        FeatureHighlightCard(
            icon = Icons.Default.Shield,
            title = "Default Preset (Supervised)",
            description = "Safest option for new users. AMC asks for your explicit confirmation (Approve / Deny) before running any system command."
        )

        FeatureHighlightCard(
            icon = Icons.Default.Bolt,
            title = "Turbo Preset (Autopilot)",
            description = "Executes safe commands autonomously without asking for approval each time. Best for long, multi-step tasks."
        )

        FeatureHighlightCard(
            icon = Icons.Default.Lock,
            title = "Strict Mode",
            description = "Enforces restrictive sandboxing and blocks potentially dangerous commands like file deletions or privileged system calls."
        )

        // Emergency Stop Notice
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = RedEmergency.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, RedEmergency.copy(alpha = 0.4f))
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.StopCircle, contentDescription = null, tint = RedEmergency, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("Instant Emergency Stop (STOP Button)", fontWeight = FontWeight.Bold, color = TextWhite, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "A bright red STOP button appears whenever the agent is working. Tapping it immediately terminates running shell processes and halts the agent.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun StepControlsAndStart(
    onNavigateToSetup: () -> Unit,
    onNavigateToChat: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.RocketLaunch, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "You Are All Set!",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                )
                Text(
                    text = "Start exploring AMC",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )
            }
        }

        Text(
            text = "Here is a quick summary of the bottom navigation tabs:",
            color = TextSecondary,
            fontSize = 13.sp
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = DarkCard,
            border = BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TabGuideRow(icon = Icons.AutoMirrored.Filled.Chat, name = "Chat", summary = "Send requests, converse with the agent, and review artifacts.")
                HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
                TabGuideRow(icon = Icons.Default.Terminal, name = "Terminal", summary = "View raw shell commands and live stdout/stderr streams.")
                HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
                TabGuideRow(icon = Icons.Default.Build, name = "Setup", summary = "Check Termux bridge status, pair auth tokens, and manage battery settings.")
                HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
                TabGuideRow(icon = Icons.Default.Settings, name = "Settings", summary = "Configure AI models, API keys, fallback providers, and guardrails.")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "WHERE WOULD YOU LIKE TO GO FIRST?",
            style = MaterialTheme.typography.labelSmall.copy(
                color = TextMuted,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        )

        Button(
            onClick = onNavigateToSetup,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentPrimary,
                contentColor = Color.Black
            ),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Go to Setup (Pair Termux)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        OutlinedButton(
            onClick = onNavigateToChat,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, BorderSubtle),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp), tint = AccentPrimary)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Go to Chat", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun FeatureHighlightCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = DarkCard,
        border = BorderStroke(1.dp, BorderSubtle)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = AccentPrimary.copy(alpha = 0.12f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.Bold, color = TextWhite, fontSize = 13.5.sp)
                Spacer(modifier = Modifier.height(3.dp))
                Text(text = description, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun TabGuideRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    summary: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = name, fontWeight = FontWeight.Bold, color = TextWhite, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = summary, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BadgeNumber(number: String) {
    Surface(
        shape = CircleShape,
        color = AccentPrimary,
        modifier = Modifier.size(20.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = number, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}
