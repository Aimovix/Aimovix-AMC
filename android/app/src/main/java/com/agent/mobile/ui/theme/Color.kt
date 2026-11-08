package com.agent.mobile.ui.theme

import androidx.compose.ui.graphics.Color

// Dark palette built from tone, not from borders or glows. Depth comes from three surface steps
// (background, card, raised) and text has three levels. Colour is reserved for meaning: the accent
// marks the primary action and "connected", red and amber mark risk.

val DarkBackground = Color(0xFF000000)        // true black: content leads, OLED-friendly
val DarkSurface = Color(0xFF111113)           // grouped areas, drawers
val DarkCard = Color(0xFF1C1C1E)              // cards and tool activity
val DarkCardElevated = Color(0xFF2C2C2E)      // user messages, raised buttons
val BorderSubtle = Color(0xFF262628)          // separators only
val BorderLight = Color(0xFF3A3A3C)

val AccentPrimary = Color(0xFF34D399)         // primary action, connected state
val AccentPrimaryHover = Color(0xFF10B981)
val AccentSecondary = Color(0xFF0EA5E9)
val AccentIndigo = Color(0xFF6366F1)

// Compatibility aliases
val GreenPrimary = AccentPrimary
val GreenSecondary = AccentPrimaryHover
val BlueAccent = AccentSecondary

val TextWhite = Color(0xFFF5F5F7)
val TextSecondary = Color(0xFFAEAEB2)
val TextMuted = Color(0xFF8E8E93)

val RedEmergency = Color(0xFFFF453A)
val RedText = Color(0xFFFF6A61)               // red text on dark surfaces, readable at small sizes
val YellowWarning = Color(0xFFFF9F0A)
val StatusOnline = Color(0xFF30D158)

val TerminalBg = Color(0xFF0A0A0B)
val TerminalBar = Color(0xFF141416)
val TerminalText = Color(0xFFE5E5EA)
val TerminalGreen = Color(0xFF7EE2A8)
val TerminalDim = Color(0xFF8E8E93)
