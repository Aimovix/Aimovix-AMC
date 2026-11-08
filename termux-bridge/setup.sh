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
echo "[1/5] Installing Python, Git, curl, jq, Termux:API, and cronie..."
pkg update -y < /dev/null
pkg install -y python git curl jq termux-api cronie < /dev/null

echo "[2/5] Installing the bridge and CLI..."
mkdir -p "$TARGET_DIR" "$PREFIX/bin"
STAGING_DIR="$(mktemp -d "$TARGET_DIR/.install.XXXXXX")"
trap 'rm -rf "$STAGING_DIR"' EXIT
for name in bridge_daemon.py amc requirements.txt local_model_manager.sh; do
    if [ -n "$SCRIPT_DIR" ] && [ -s "$SCRIPT_DIR/$name" ]; then
        cp "$SCRIPT_DIR/$name" "$STAGING_DIR/$name"
    else
        curl --fail --show-error --location "$REPOSITORY_BASE/$name" -o "$STAGING_DIR/$name"
    fi
done
echo "[3/5] Installing Python dependencies..."
python -m pip install -r "$STAGING_DIR/requirements.txt"

# Publish only after every download and dependency installation succeeds.
chmod 700 "$TARGET_DIR"
for name in bridge_daemon.py amc requirements.txt local_model_manager.sh; do
    mv "$STAGING_DIR/$name" "$TARGET_DIR/$name"
done
cp "$TARGET_DIR/amc" "$PREFIX/bin/amc"
chmod +x "$PREFIX/bin/amc" "$TARGET_DIR/local_model_manager.sh"

# Skill library: the agent reads and writes reusable how-to files here.
mkdir -p "$HOME/.amc/skills"
if [ ! -e "$HOME/.amc/skills/README.md" ]; then
    cat > "$HOME/.amc/skills/README.md" <<'SKILLS'
# AMC skills

Each Markdown file in this folder is a reusable skill for the AMC agent:
a short title, when to use it, and the exact commands or steps.
The agent checks this folder for recurring tasks and saves new skills here
after solving something that is likely to come up again.
You can add, edit or delete skills yourself at any time.
SKILLS
fi

# Let the AMC app start the bridge and pair itself through Termux's RUN_COMMAND intent.
# Only apps holding the user-granted RUN_COMMAND permission can use it. Opt out with AMC_ALLOW_EXTERNAL_APPS=0.
if [ "${AMC_ALLOW_EXTERNAL_APPS:-1}" = "1" ]; then
    mkdir -p "$HOME/.termux"
    if ! grep -qE '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true' "$HOME/.termux/termux.properties" 2>/dev/null; then
        printf '\n# Lets the AMC app start the bridge and pair itself (Termux RUN_COMMAND).\nallow-external-apps = true\n' >> "$HOME/.termux/termux.properties"
    fi
    command -v termux-reload-settings >/dev/null 2>&1 && termux-reload-settings >/dev/null 2>&1 || true
fi

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
echo "3. In AMC -> Setup, tap 'Auto-connect' (or run 'amc token' and enter the token)."
echo "Authentication is required, including connections from the same phone."
echo "Use 'amc status', 'amc logs', or 'amc boost' for troubleshooting."
echo "Android and manufacturer restrictions can still stop background processes."
