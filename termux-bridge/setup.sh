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

# 4. Setup working directory and scripts
echo "⚙️ [4/5] Richte Agent-Bridge Verzeichnis ein..."
TARGET_DIR="$HOME/.termux_agent"
mkdir -p "$TARGET_DIR"

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bridge_daemon.py"

if [ -f "$SCRIPT_PATH" ]; then
    cp "$SCRIPT_PATH" "$TARGET_DIR/bridge_daemon.py"
else
    # Fallback if executed via curl pipe
    echo "⬇️ Lade bridge_daemon.py aus Aimovix-Repository..."
    curl -sL "https://raw.githubusercontent.com/Aimovix/Aimovix/main/termux-bridge/bridge_daemon.py" -o "$TARGET_DIR/bridge_daemon.py"
fi

# Make run script
cat << 'RUN_SCRIPT' > "$TARGET_DIR/run.sh"
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock 2>/dev/null || true
cd "$HOME/.termux_agent"
python bridge_daemon.py
RUN_SCRIPT
chmod +x "$TARGET_DIR/run.sh"

# 5. Start the Bridge Daemon
echo "🚀 [5/5] Starte Termux Bridge Daemon..."
cd "$TARGET_DIR"

# Launch daemon in background or foreground
echo ""
echo "================================================================"
echo "✅ SETUP ERFOLGREICH ABGESCHLOSSEN!"
echo "================================================================"
echo ""
echo "Der Bridge-Dienst startet jetzt."
echo "Sobald der Token angezeigt wird, trage ihn in deiner Android App ein."
echo ""
echo "Tipp: Um den Dienst später jederzeit neu zu starten, tippe:"
echo "      ~/.termux_agent/run.sh"
echo "================================================================"
echo ""

exec python "$TARGET_DIR/bridge_daemon.py"
