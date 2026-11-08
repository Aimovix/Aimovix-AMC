package com.agent.mobile.ui.legal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.agent.mobile.ui.theme.*
import java.util.Locale

private fun defaultLanguage(): LegalTexts.Language =
    if (Locale.getDefault().language == "de") LegalTexts.Language.DE else LegalTexts.Language.EN

@Composable
private fun LanguageToggle(language: LegalTexts.Language, onChange: (LegalTexts.Language) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LegalTexts.Language.entries.forEach { lang ->
            FilterChip(
                selected = language == lang,
                onClick = { onChange(lang) },
                label = { Text(if (lang == LegalTexts.Language.DE) "Deutsch" else "English", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentPrimary.copy(alpha = 0.2f),
                    selectedLabelColor = AccentPrimary,
                    labelColor = TextSecondary
                )
            )
        }
    }
}

@Composable
private fun LegalSectionsList(language: LegalTexts.Language) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LegalTexts.sections(language).forEach { section ->
            Column {
                Text(section.title, style = MaterialTheme.typography.titleSmall.copy(color = TextWhite, fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(4.dp))
                Text(section.body, style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp))
            }
        }
    }
}

/**
 * Shown before the app can be used, and again whenever [LegalTexts.VERSION] increases.
 * Every checkbox must be ticked before "Accept" is enabled.
 */
@Composable
fun LegalConsentScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    var language by remember { mutableStateOf(defaultLanguage()) }
    val labels = LegalTexts.consentLabels(language)
    val checks = remember { mutableStateListOf(false, false, false) }
    val de = language == LegalTexts.Language.DE

    Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Gavel, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (de) "Nutzungsbedingungen & Datenschutz" else "Terms of use & privacy",
                    style = MaterialTheme.typography.titleLarge.copy(color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 19.sp),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            LanguageToggle(language) { language = it }
            Spacer(Modifier.height(8.dp))

            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = DarkCard,
                border = BorderStroke(1.dp, BorderSubtle)
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
                    LegalSectionsList(language)
                }
            }

            Spacer(Modifier.height(10.dp))
            labels.forEachIndexed { i, label ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { checks[i] = !checks[i] }
                        .padding(vertical = 2.dp)
                ) {
                    Checkbox(
                        checked = checks[i],
                        onCheckedChange = { checks[i] = it },
                        colors = CheckboxDefaults.colors(checkedColor = AccentPrimary, checkmarkColor = Color.Black)
                    )
                    Text(label, style = MaterialTheme.typography.bodySmall.copy(color = TextWhite, fontSize = 12.sp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onDecline,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, BorderSubtle)
                ) { Text(if (de) "Ablehnen" else "Decline", color = TextSecondary) }
                Button(
                    onClick = onAccept,
                    enabled = checks.all { it },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = Color.Black)
                ) { Text(if (de) "Akzeptieren" else "Accept", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Read-only view of the legal texts, opened from Settings. */
@Composable
fun LegalTextsDialog(onDismiss: () -> Unit) {
    var language by remember { mutableStateOf(defaultLanguage()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f),
            shape = RoundedCornerShape(14.dp),
            color = DarkCard,
            border = BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                LanguageToggle(language) { language = it }
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    LegalSectionsList(language)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(if (language == LegalTexts.Language.DE) "Schließen" else "Close", color = AccentPrimary)
                }
            }
        }
    }
}
