#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# AMC - AI Mobile Center (by Aimovix) - One-Click Setup Script
# ==============================================================================

export DEBIAN_FRONTEND=noninteractive
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PREFIX
export PATH="$PREFIX/bin:$PREFIX/bin/applets:$PATH"

echo "================================================================"
echo "🤖 AMC - AI Mobile Center • 1-Klick Setup & Hintergrund-Boost"
echo "================================================================"

# 1. Prevent Android from sleeping while agent runs (Acquire Wake Lock)
echo "🔋 [1/6] Sichere Hintergrundausführung (CPU-Wake-Lock)..."
if command -v termux-wake-lock >/dev/null 2>&1; then
    termux-wake-lock >/dev/null 2>&1 || true
    echo "   ✅ Wake-Lock aktiviert (Termux Foreground-Service aktiv)."
else
    echo "   ⚠️ termux-wake-lock wird nach Paketinstallation aktiviert."
fi

# 2. Update package lists and install required tools (Python, Git, Curl, JQ, Termux:API)
echo "📦 [2/6] Installiere benötigte Pakete (Python, Termux:API, Git, Curl, JQ)..."
pkg update -y < /dev/null || true
pkg install -y python git curl jq termux-api < /dev/null || apt-get install -y python git curl jq termux-api < /dev/null || true

# Erneut Wake-Lock anfordern falls termux-tools aktualisiert wurden
if command -v termux-wake-lock >/dev/null 2>&1; then
    termux-wake-lock >/dev/null 2>&1 || true
fi

# 3. Install Python dependencies (websockets)
echo "🐍 [3/6] Installiere Python-Bibliothek 'websockets'..."
python -m ensurepip >/dev/null 2>&1 || true
pip install --break-system-packages websockets 2>/dev/null || \
pip install websockets 2>/dev/null || \
python -m pip install --break-system-packages websockets 2>/dev/null || \
python -m pip install websockets 2>/dev/null || true

if ! python -c "import websockets" >/dev/null 2>&1; then
    echo "   ⚠️ Versuche erzwungene websockets-Installation..."
    pip install --break-system-packages --no-cache-dir websockets || true
fi

# 4. Setup working directory, daemon script, and amc CLI tool
echo "⚙️ [4/6] Richte AMC Service und CLI-Tool ein..."
TARGET_DIR="$HOME/.termux_agent"
mkdir -p "$TARGET_DIR"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd || echo "")"

# Install bridge_daemon.py
if [ -n "$SCRIPT_DIR" ] && [ -s "$SCRIPT_DIR/bridge_daemon.py" ]; then
    cp "$SCRIPT_DIR/bridge_daemon.py" "$TARGET_DIR/bridge_daemon.py"
else
    echo "   ⬇️ Lade bridge_daemon.py..."
    curl -sL "https://raw.githubusercontent.com/Aimovix/Aimovix-AMC/main/termux-bridge/bridge_daemon.py" -o "$TARGET_DIR/bridge_daemon.py"
fi

# Install 'amc' CLI command into Termux PATH ($PREFIX/bin/amc)
BIN_DIR="${PREFIX}/bin"
mkdir -p "$BIN_DIR"

if [ -n "$SCRIPT_DIR" ] && [ -s "$SCRIPT_DIR/amc" ]; then
    cp "$SCRIPT_DIR/amc" "$BIN_DIR/amc"
else
    echo "   ⬇️ Lade amc CLI-Tool..."
    curl -sL "https://raw.githubusercontent.com/Aimovix/Aimovix-AMC/main/termux-bridge/amc" -o "$BIN_DIR/amc"
fi
chmod +x "$BIN_DIR/amc"
cp "$BIN_DIR/amc" "$TARGET_DIR/amc" 2>/dev/null || true

# Autostart in .bashrc und Termux:Boot hinterlegen
"$BIN_DIR/amc" autostart >/dev/null 2>&1 || true

# 5. Start the Bridge Daemon in Background (with detached session)
echo "🚀 [5/6] Starte AMC Hintergrunddienst..."
"$BIN_DIR/amc" restart

# 6. Fordere Android Akku-Optimierung und Benachrichtigungs-Berechtigung an
echo "⚡ [6/6] Fordere Hintergrund-Berechtigungen von Android an..."
if command -v am >/dev/null 2>&1; then
    # Dialog für Akku-Ausnahme anfordern
    am start -a android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -d package:com.termux >/dev/null 2>&1 || \
    am start -a android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS >/dev/null 2>&1 || true
    # Benachrichtigungen anfordern für dauerhaften Foreground Service (Android 13+)
    am start -a android.settings.APP_NOTIFICATION_SETTINGS --es android.provider.extra.APP_PACKAGE com.termux >/dev/null 2>&1 || true
fi

echo ""
echo "================================================================"
echo "✅ AMC SERVICE ERFOLGREICH EINGERICHTET & GESTARTET!"
echo "================================================================"
echo ""
echo "🛑 DAMIT DIE VERBINDUNG DAUERHAFT BLEIBT:"
echo " 1. Akku-Optimierung: Bitte im System-Dialog 'Zulassen' wählen"
echo "    (bzw. unter Einstellungen -> Apps -> Termux -> Akku: 'Uneingeschränkt')."
echo " 2. Benachrichtigungen: 'Zulassen' wählen, damit der Foreground-Service aktiv bleibt."
echo " 3. Termux NICHT mit 'exit' beenden und NICHT aus den letzten Apps wischen!"
echo "    Termux einfach geöffnet im Hintergrund belassen (Home-Taste)."
echo " 4. Bei Android 12/13/14+: Falls Termux trotzdem einfriert,"
echo "    in den Entwickleroptionen 'Kindprozess-Beschränkungen deaktivieren'."
echo ""
echo "Hilfreiche Befehle:"
echo "   amc boost      - Reaktiviert Wake-Lock & Akku-Ausnahme"
echo "   amc status     - Zeigt PID, Port 8765, Akku & Token"
echo "   amc logs       - Zeigt Live-Ausgaben des Daemons"
echo "   amc restart    - Startet den Hintergrunddienst neu"
echo "   amc stop       - Beendet den Dienst"
echo "================================================================"
echo ""

# Automatischer Wechsel zurück zur AMC-App
if command -v am >/dev/null 2>&1; then
    sleep 2
    am start -n com.agent.mobile/.MainActivity >/dev/null 2>&1 || \
    monkey -p com.agent.mobile -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
fi
