#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# AMC - AI Mobile Center (by Aimovix) - One-Click Setup Script
# ==============================================================================

export DEBIAN_FRONTEND=noninteractive

echo "🚀 [1/5] Initialisiere AMC Agent Setup..."

# 1. Prevent Android from sleeping while agent runs
if command -v termux-wake-lock >/dev/null 2>&1; then
    termux-wake-lock >/dev/null 2>&1 || true
    echo "🔋 Wake-Lock aktiviert (Hintergrundausführung gesichert)."
fi

# 2. Update package lists and install required tools (Python, Git, Curl, JQ, Termux:API)
# Note: In Termux, pip is included directly inside 'python'. 'python-pip' does NOT exist.
echo "📦 [2/5] Installiere benötigte Pakete (Python, Termux:API, Git, Curl, JQ)..."
pkg update -y < /dev/null || true
pkg install -y python git curl jq termux-api < /dev/null || apt-get install -y python git curl jq termux-api < /dev/null || true

# 3. Install Python dependencies (websockets)
echo "🐍 [3/5] Installiere Python-Bibliothek 'websockets'..."
python -m ensurepip >/dev/null 2>&1 || true
pip install --break-system-packages websockets 2>/dev/null || \
pip install websockets 2>/dev/null || \
python -m pip install --break-system-packages websockets 2>/dev/null || \
python -m pip install websockets 2>/dev/null || true

if ! python -c "import websockets" >/dev/null 2>&1; then
    echo "⚠️ Versuche erzwungene websockets-Installation..."
    pip install --break-system-packages --no-cache-dir websockets || true
fi

# 4. Setup working directory, daemon script, and amc CLI tool
echo "⚙️ [4/5] Richte AMC Service und CLI-Tool ein..."
TARGET_DIR="$HOME/.termux_agent"
mkdir -p "$TARGET_DIR"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd || echo "")"

# Install bridge_daemon.py
if [ -n "$SCRIPT_DIR" ] && [ -s "$SCRIPT_DIR/bridge_daemon.py" ]; then
    cp "$SCRIPT_DIR/bridge_daemon.py" "$TARGET_DIR/bridge_daemon.py"
else
    echo "⬇️ Lade bridge_daemon.py aus GitHub..."
    curl -sL "https://raw.githubusercontent.com/Aimovix/Aimovix-AMC/main/termux-bridge/bridge_daemon.py" -o "$TARGET_DIR/bridge_daemon.py"
fi

# Install 'amc' CLI command into Termux PATH ($PREFIX/bin/amc)
BIN_DIR="${PREFIX:-/data/data/com.termux/files/usr}/bin"
mkdir -p "$BIN_DIR"

if [ -n "$SCRIPT_DIR" ] && [ -s "$SCRIPT_DIR/amc" ]; then
    cp "$SCRIPT_DIR/amc" "$BIN_DIR/amc"
else
    echo "⬇️ Lade amc CLI-Tool aus GitHub..."
    curl -sL "https://raw.githubusercontent.com/Aimovix/Aimovix-AMC/main/termux-bridge/amc" -o "$BIN_DIR/amc"
fi
chmod +x "$BIN_DIR/amc"
cp "$BIN_DIR/amc" "$TARGET_DIR/amc" 2>/dev/null || true

# Autostart in .bashrc und Termux:Boot hinterlegen
"$BIN_DIR/amc" autostart >/dev/null 2>&1 || true

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
echo "💡 WICHTIG FÜR ANDROID-HINTERGRUNDBETRIEB:"
echo "   In den Android-Einstellungen deines Handys:"
echo "   -> Apps -> Termux -> Akku / Akkunutzung"
echo "   -> Wähle 'Nicht optimiert' bzw. 'Uneingeschränkt'"
echo ""
echo "Hilfreiche Termux-Befehle:"
echo "   amc status     - Zeigt aktuellen Status, Port & Token"
echo "   amc logs       - Zeigt Live-Ausgaben des Daemons"
echo "   amc restart    - Startet den Hintergrunddienst neu"
echo "   amc stop       - Beendet den Dienst"
echo "================================================================"
echo ""
