#!/data/data/com.termux/files/usr/bin/bash
# AMC - AI Mobile Center: install the authenticated device-local bridge.
set -e
umask 077

export DEBIAN_FRONTEND=noninteractive
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PREFIX
export PATH="$PREFIX/bin:$PREFIX/bin/applets:$PATH"
TARGET_DIR="$HOME/.termux_agent"
AMC_REF="${AMC_REF:-main}"
REPOSITORY_BASE="https://raw.githubusercontent.com/Aimovix/Aimovix-AMC/${AMC_REF}/termux-bridge"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd || true)"

echo "AMC - AI Mobile Center setup (ref: $AMC_REF)"
echo "[1/5] Installing Python, Git, curl, jq, and Termux:API..."
pkg update -y < /dev/null
pkg install -y python git curl jq termux-api < /dev/null

echo "[2/5] Installing the bridge and CLI..."
mkdir -p "$TARGET_DIR" "$PREFIX/bin"
for name in bridge_daemon.py amc requirements.txt local_model_manager.sh; do
    if [ -n "$SCRIPT_DIR" ] && [ -s "$SCRIPT_DIR/$name" ]; then
        cp "$SCRIPT_DIR/$name" "$TARGET_DIR/$name"
    else
        curl --fail --show-error --location "$REPOSITORY_BASE/$name" -o "$TARGET_DIR/$name"
    fi
done
chmod 700 "$TARGET_DIR"
cp "$TARGET_DIR/amc" "$PREFIX/bin/amc"
chmod +x "$PREFIX/bin/amc" "$TARGET_DIR/local_model_manager.sh"

echo "[3/5] Installing Python dependencies..."
python -m pip install -r "$TARGET_DIR/requirements.txt"

echo "[4/5] Enabling background startup..."
amc autostart
amc restart

echo "[5/5] Opening Android background-permission settings..."
if command -v am >/dev/null 2>&1; then
    am start -a android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -d package:com.termux >/dev/null 2>&1 ||
        am start -a android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS >/dev/null 2>&1 || true
    am start -a android.settings.APP_NOTIFICATION_SETTINGS --es android.provider.extra.APP_PACKAGE com.termux >/dev/null 2>&1 || true
fi

echo ""
echo "Setup complete."
echo "1. Set Termux battery usage to Unrestricted / Not optimized."
echo "2. Allow Termux notifications and leave it running in the background."
echo "3. Run 'amc token' and enter the token in AMC -> Setup."
echo "Authentication is required, including connections from the same phone."
echo "Use 'amc status', 'amc logs', or 'amc boost' for troubleshooting."
echo "Android and manufacturer restrictions can still stop background processes."
