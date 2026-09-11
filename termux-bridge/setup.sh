#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# AMC - AI Mobile Center (by Aimovix) - One-Click Setup Script
# ==============================================================================

set -e

echo "🚀 [1/5] Initialisiere AMC Agent Setup..."

# 1. Prevent Android from sleeping while agent runs
if command -v termux-wake-lock &> /dev/null; then
    termux-wake-lock
    echo "🔋 Wake-Lock aktiviert (Hintergrundausführung gesichert)."
fi

# 2. Update Termux repositories and install core packages
echo "📦 [2/5] Installiere benötigte Pakete (Python, Termux:API, Git, Curl)..."
pkg update -y
pkg install -y python python-pip git curl jq termux-api

# 3. Install Python dependencies
echo "🐍 [3/5] Installiere Python-Bibliotheken (websockets)..."
pip install websockets 2>/dev/null || pip install --break-system-packages websockets 2>/dev/null || python -m pip install websockets 2>/dev/null || python -m pip install --break-system-packages websockets 2>/dev/null || true

# 4. Setup working directory, daemon script, and amc CLI tool
echo "⚙️ [4/5] Richte AMC Service und CLI-Tool ein..."
TARGET_DIR="$HOME/.termux_agent"
mkdir -p "$TARGET_DIR"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd || echo "")"

if [ -n "$SCRIPT_DIR" ] && [ -f "$SCRIPT_DIR/bridge_daemon.py" ]; then
    cp "$SCRIPT_DIR/bridge_daemon.py" "$TARGET_DIR/bridge_daemon.py"
else
    echo "⬇️ Lade bridge_daemon.py aus GitHub..."
    curl -sL "https://raw.githubusercontent.com/Aimovix/Aimovix-AMC/main/termux-bridge/bridge_daemon.py" -o "$TARGET_DIR/bridge_daemon.py"
fi

# Install 'amc' CLI command into Termux PATH ($PREFIX/bin/amc)
BIN_DIR="${PREFIX:-/data/data/com.termux/files/usr}/bin"
mkdir -p "$BIN_DIR"

if [ -n "$SCRIPT_DIR" ] && [ -f "$SCRIPT_DIR/amc" ]; then
    cp "$SCRIPT_DIR/amc" "$BIN_DIR/amc"
else
    echo "⬇️ Lade amc CLI-Tool aus GitHub..."
    curl -sL "https://raw.githubusercontent.com/Aimovix/Aimovix-AMC/main/termux-bridge/amc" -o "$BIN_DIR/amc"
fi
chmod +x "$BIN_DIR/amc"
cp "$BIN_DIR/amc" "$TARGET_DIR/amc" 2>/dev/null || true

# Richte Autostart bei Boot und Shell-Start ein
"$BIN_DIR/amc" autostart > /dev/null 2>&1 || true

# 5. Start the Bridge Daemon in Background
echo "🚀 [5/5] Starte AMC Hintergrunddienst..."
"$BIN_DIR/amc" restart

echo ""
echo "================================================================"
echo "✅ AMC SERVICE ERFOLGREICH EINGERICHTET & GESTARTET!"
echo "================================================================"
echo ""
echo "Der Dienst läuft jetzt dauerhaft im Hintergrund (auch wenn Termux minimiert wird)."
echo ""
echo "💡 WICHTIGER HINWEIS FÜR ANDROID-HINTERGRUNDBETRIEB:"
echo "   Gehe in die Android-Einstellungen deines Handys:"
echo "   -> Apps -> Termux -> Akku / Akkunutzung"
echo "   -> Wähle 'Nicht optimiert' bzw. 'Uneingeschränkt'"
echo "   Dadurch verhindert Android, dass Termux im Hintergrund pausiert wird."
echo ""
echo "Hilfreiche Termux-Befehle:"
echo "   amc status     - Zeigt aktuellen Status, Port & Token"
echo "   amc logs       - Zeigt Live-Ausgaben des Daemons"
echo "   amc restart    - Startet den Hintergrunddienst neu"
echo "   amc stop       - Beendet den Dienst"
echo "================================================================"
echo ""

