#!/usr/bin/env python3
"""
AMC - AI Mobile Center Bridge Daemon
High-performance WebSocket & HTTP bridge for controlling Termux from the Android Agent App.
Supports live streaming of stdout/stderr, process interruption, resource stats, and auth tokens.
"""

import asyncio
import json
import os
import signal
import sys
import subprocess
import shutil
import shlex
from pathlib import Path

# Try importing websockets; auto-install if missing
try:
    import websockets
except ImportError:
    print("[INFO] 'websockets' Paket wird nachinstalliert...", file=sys.stderr)
    try:
        subprocess.run([sys.executable, "-m", "pip", "install", "--break-system-packages", "websockets"], check=True)
        import websockets
    except Exception:
        try:
            subprocess.run([sys.executable, "-m", "pip", "install", "websockets"], check=True)
            import websockets
        except Exception as e:
            print(f"[ERROR] 'websockets' konnte nicht geladen werden: {e}", file=sys.stderr)
            sys.exit(1)

PORT = int(os.environ.get("BRIDGE_PORT", 8765))
HOST = os.environ.get("BRIDGE_HOST", "0.0.0.0")
TOKEN_FILE = Path.home() / ".termux_agent_token"
AGENT_DIR = Path.home() / ".termux_agent"
PID_FILE = AGENT_DIR / "daemon.pid"
DEFAULT_CWD = os.environ.get("HOME", os.path.expanduser("~"))

current_process = None
current_cwd = DEFAULT_CWD

# Ensure full Termux binary paths are present in PATH
TERMUX_PREFIX = "/data/data/com.termux/files/usr"
TERMUX_BIN = f"{TERMUX_PREFIX}/bin"
TERMUX_APPLETS = f"{TERMUX_PREFIX}/bin/applets"

ENV = os.environ.copy()
if os.path.exists(TERMUX_BIN):
    ENV["PREFIX"] = TERMUX_PREFIX
    current_path = ENV.get("PATH", "")
    if TERMUX_BIN not in current_path:
        ENV["PATH"] = f"{TERMUX_BIN}:{TERMUX_APPLETS}:{current_path}"

def ensure_wake_lock():
    """Ensure Termux CPU wake lock is held to prevent Android deep sleep."""
    wake_lock = shutil.which("termux-wake-lock", path=ENV.get("PATH"))
    if wake_lock:
        try:
            subprocess.run([wake_lock], env=ENV, timeout=3, capture_output=True)
        except Exception:
            pass

def update_termux_notification(status_text="Bridge aktiv • Port 8765"):
    """Post or update an ongoing notification via termux-api to prevent background freeze."""
    termux_notif = shutil.which("termux-notification", path=ENV.get("PATH"))
    if termux_notif:
        try:
            subprocess.run([
                termux_notif,
                "--id", "amc_bridge",
                "--title", "AMC Termux Bridge (Aktiv)",
                "--content", status_text,
                "--ongoing",
                "--priority", "high"
            ], env=ENV, timeout=3, capture_output=True)
        except Exception:
            pass

def remove_termux_notification():
    """Remove the ongoing Termux notification on shutdown."""
    termux_notif_rm = shutil.which("termux-notification-remove", path=ENV.get("PATH"))
    if termux_notif_rm:
        try:
            subprocess.run([termux_notif_rm, "amc_bridge"], env=ENV, timeout=3, capture_output=True)
        except Exception:
            pass

def load_or_generate_token() -> str:
    """Load existing auth token or create a secure random token."""
    if os.environ.get("BRIDGE_NO_AUTH", "0") == "1" or "--no-auth" in sys.argv:
        return ""
    if TOKEN_FILE.exists():
        try:
            t = TOKEN_FILE.read_text().strip()
            if t:
                return t
        except Exception:
            pass
    import secrets
    token = secrets.token_hex(16)
    try:
        TOKEN_FILE.write_text(token)
        TOKEN_FILE.chmod(0o600)
    except Exception as e:
        print(f"[WARN] Could not write token file: {e}", file=sys.stderr)
    return token

AUTH_TOKEN = load_or_generate_token()


def get_system_info() -> dict:
    """Collect system, memory, storage, and battery status via termux-api or standard Linux tools."""
    info = {
        "os": sys.platform,
        "cwd": current_cwd,
        "python_version": sys.version.split()[0],
        "has_termux_api": shutil.which("termux-battery-status", path=ENV.get("PATH")) is not None,
        "has_llama": shutil.which("llama-server", path=ENV.get("PATH")) is not None,
    }

    # Battery
    if info["has_termux_api"]:
        try:
            res = subprocess.run(["termux-battery-status"], capture_output=True, text=True, timeout=3, env=ENV)
            if res.returncode == 0 and res.stdout.strip():
                info["battery"] = json.loads(res.stdout)
        except Exception:
            pass

    # Disk usage in HOME
    try:
        total, used, free = shutil.disk_usage(current_cwd)
        info["disk"] = {
            "total_mb": total // (1024 * 1024),
            "free_mb": free // (1024 * 1024),
            "used_mb": used // (1024 * 1024)
        }
    except Exception:
        pass

    return info

def handle_cd_command(cmd: str, target_cwd: str) -> tuple[bool, str, str]:
    """
    Checks if cmd is a pure cd command and updates current_cwd.
    Returns: (is_cd, new_or_curr_cwd, error_message)
    """
    trimmed = cmd.strip()
    if trimmed == "cd" or trimmed == "cd ~":
        return True, DEFAULT_CWD, ""

    if trimmed.startswith("cd ") and "&&" not in trimmed and ";" not in trimmed and "|" not in trimmed:
        raw_target = trimmed[3:].strip().strip("\"'")
        expanded = os.path.expanduser(raw_target)
        full_path = os.path.normpath(os.path.join(target_cwd, expanded))
        if os.path.isdir(full_path):
            return True, full_path, ""
        else:
            return True, target_cwd, f"Verzeichnis nicht gefunden: {raw_target}\n"

    return False, target_cwd, ""

async def run_command_streaming(websocket, cmd: str, execution_id: str, cwd: str | None = None):
    """Executes a command and streams stdout/stderr chunks in real-time over WebSocket."""
    global current_process, current_cwd
    target_cwd = cwd or current_cwd

    # 1. Handle pure cd commands directly
    is_cd, new_cwd, cd_err = handle_cd_command(cmd, target_cwd)
    if is_cd:
        if cd_err:
            await websocket.send(json.dumps({
                "type": "stderr",
                "execution_id": execution_id,
                "data": cd_err
            }))
            await websocket.send(json.dumps({
                "type": "completed",
                "execution_id": execution_id,
                "exit_code": 1,
                "cwd": current_cwd
            }))
        else:
            current_cwd = new_cwd
            await websocket.send(json.dumps({
                "type": "stdout",
                "execution_id": execution_id,
                "data": f"Verzeichnis gewechselt zu: {current_cwd}\n"
            }))
            await websocket.send(json.dumps({
                "type": "completed",
                "execution_id": execution_id,
                "exit_code": 0,
                "cwd": current_cwd
            }))
        return

    # Use bash shell if available, otherwise default system shell
    shell_bin = "/data/data/com.termux/files/usr/bin/bash"
    if not os.path.exists(shell_bin):
        shell_bin = shutil.which("bash", path=ENV.get("PATH")) or shutil.which("sh") or "sh"

    try:
        current_process = await asyncio.create_subprocess_shell(
            cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            cwd=target_cwd,
            executable=shell_bin if sys.platform != "win32" else None,
            env=ENV,
            preexec_fn=os.setsid if sys.platform != "win32" else None
        )

        async def read_stream(stream, msg_type: str):
            while True:
                line = await stream.readline()
                if not line:
                    break
                decoded = line.decode("utf-8", errors="replace")
                try:
                    await websocket.send(json.dumps({
                        "type": msg_type,
                        "execution_id": execution_id,
                        "data": decoded
                    }))
                except Exception:
                    break

        # Stream stdout and stderr concurrently
        await asyncio.gather(
            read_stream(current_process.stdout, "stdout"),
            read_stream(current_process.stderr, "stderr")
        )

        exit_code = await current_process.wait()
        await websocket.send(json.dumps({
            "type": "completed",
            "execution_id": execution_id,
            "exit_code": exit_code,
            "cwd": current_cwd
        }))

    except asyncio.CancelledError:
        if current_process:
            try:
                if sys.platform != "win32":
                    os.killpg(os.getpgid(current_process.pid), signal.SIGTERM)
                else:
                    current_process.terminate()
            except Exception:
                pass
        raise
    except Exception as e:
        await websocket.send(json.dumps({
            "type": "error",
            "execution_id": execution_id,
            "error": str(e)
        }))
    finally:
        current_process = None

async def handle_connection(websocket):
    """Handle incoming WebSocket client connection with authentication."""
    global current_process, current_cwd
    authenticated = False

    try:
        async for message in websocket:
            try:
                msg = json.loads(message)
            except json.JSONDecodeError:
                await websocket.send(json.dumps({"type": "error", "error": "Ungültiges JSON-Format"}))
                continue

            action = msg.get("action")

            # 1. Authentication handshake
            if action == "auth":
                provided_token = msg.get("token", "")
                if provided_token == AUTH_TOKEN or not AUTH_TOKEN:
                    authenticated = True
                    await websocket.send(json.dumps({
                        "type": "auth_ok",
                        "system": get_system_info(),
                        "cwd": current_cwd
                    }))
                else:
                    await websocket.send(json.dumps({
                        "type": "auth_fail",
                        "error": "Authentifizierung fehlgeschlagen: Token ungültig."
                    }))
                continue

            if not authenticated:
                await websocket.send(json.dumps({
                    "type": "error",
                    "error": "Nicht authentifiziert. Zuerst {'action': 'auth', 'token': '...'} senden."
                }))
                continue

            # 2. Ping / Keepalive
            if action == "ping":
                await websocket.send(json.dumps({
                    "type": "pong",
                    "timestamp": msg.get("timestamp", 0)
                }))

            # 3. System info
            elif action == "sys_info":
                await websocket.send(json.dumps({
                    "type": "sys_info",
                    "data": get_system_info()
                }))

            # 4. Execute shell command
            elif action == "execute":
                cmd = msg.get("command", "").strip()
                execution_id = msg.get("execution_id", "default")
                cwd = msg.get("cwd", current_cwd)
                if not cmd:
                    await websocket.send(json.dumps({
                        "type": "error",
                        "execution_id": execution_id,
                        "error": "Leerer Befehl übergeben"
                    }))
                    continue
                await run_command_streaming(websocket, cmd, execution_id, cwd)

            # 5. Interrupt running command
            elif action == "interrupt":
                if current_process and current_process.returncode is None:
                    try:
                        if sys.platform != "win32":
                            os.killpg(os.getpgid(current_process.pid), signal.SIGINT)
                        else:
                            current_process.terminate()
                        await websocket.send(json.dumps({
                            "type": "interrupted",
                            "execution_id": msg.get("execution_id", "")
                        }))
                    except Exception as e:
                        await websocket.send(json.dumps({
                            "type": "error",
                            "error": f"Abbruch fehlgeschlagen: {e}"
                        }))
                else:
                    await websocket.send(json.dumps({
                        "type": "info",
                        "message": "Kein aktiver Prozess zum Abbrechen"
                    }))

            # 6. Read file
            elif action == "read_file":
                path = os.path.expanduser(msg.get("path", ""))
                if not os.path.isabs(path):
                    path = os.path.join(current_cwd, path)
                try:
                    with open(path, "r", encoding="utf-8", errors="replace") as f:
                        content = f.read()
                    await websocket.send(json.dumps({
                        "type": "file_content",
                        "path": path,
                        "content": content
                    }))
                except Exception as e:
                    await websocket.send(json.dumps({
                        "type": "error",
                        "path": path,
                        "error": str(e)
                    }))

            # 7. Write file
            elif action == "write_file":
                path = os.path.expanduser(msg.get("path", ""))
                content = msg.get("content", "")
                if not os.path.isabs(path):
                    path = os.path.join(current_cwd, path)
                try:
                    os.makedirs(os.path.dirname(path), exist_ok=True)
                    with open(path, "w", encoding="utf-8") as f:
                        f.write(content)
                    await websocket.send(json.dumps({
                        "type": "file_written",
                        "path": path,
                        "success": True
                    }))
                except Exception as e:
                    await websocket.send(json.dumps({
                        "type": "error",
                        "path": path,
                        "error": str(e)
                    }))

    except websockets.exceptions.ConnectionClosed:
        pass
    except Exception as e:
        print(f"[ERROR] WebSocket handler error: {e}", file=sys.stderr)

async def background_watchdog():
    """Background task running alongside the websocket server to keep Termux alive."""
    while True:
        try:
            ensure_wake_lock()
            # Update notification with current battery status if available
            info = get_system_info()
            batt = info.get("battery", {})
            pct = batt.get("percentage")
            status_text = f"Port {PORT} • Akku: {pct}%" if pct is not None else f"Port {PORT} • Hintergrund aktiv"
            update_termux_notification(status_text)
        except Exception:
            pass
        await asyncio.sleep(45)

async def main():
    # Ignore SIGHUP so closing the Termux terminal session does not terminate the daemon
    if hasattr(signal, "SIGHUP"):
        try:
            signal.signal(signal.SIGHUP, signal.SIG_IGN)
        except Exception:
            pass

    # Ensure agent directory exists and write PID
    AGENT_DIR.mkdir(parents=True, exist_ok=True)
    try:
        PID_FILE.write_text(str(os.getpid()))
    except Exception as e:
        print(f"[WARN] Konnte PID-Datei nicht schreiben: {e}", file=sys.stderr)

    ensure_wake_lock()
    update_termux_notification("AMC Bridge gestartet • Port " + str(PORT))

    print(f"==================================================")
    print(f" AMC - AI Mobile Center Bridge Daemon")
    print(f" Listening on ws://{HOST}:{PORT}")
    print(f" PID: {os.getpid()}")
    if AUTH_TOKEN:
        print(f" Auth Token: {AUTH_TOKEN}")
        print(f" Token File: {TOKEN_FILE}")
        clip_cmd = shutil.which("termux-clipboard-set", path=ENV.get("PATH"))
        if clip_cmd:
            try:
                subprocess.run([clip_cmd, AUTH_TOKEN], env=ENV, timeout=2)
                print(f" [INFO] 📋 Token wurde in die Android-Zwischenablage kopiert!")
            except Exception:
                pass
    else:
        print(f" Auth Token: DEAKTIVIERT (Offener lokaler Modus)")
    print(f"==================================================")

    # Start background keep-alive watchdog
    watchdog_task = asyncio.create_task(background_watchdog())

    try:
        async with websockets.serve(
            handle_connection,
            HOST,
            PORT,
            max_size=10 * 1024 * 1024,
            ping_interval=10,
            ping_timeout=10
        ):
            await asyncio.Future()  # run forever
    finally:
        watchdog_task.cancel()
        remove_termux_notification()
        if PID_FILE.exists():
            try:
                PID_FILE.unlink()
            except Exception:
                pass

def cleanup_and_exit(signum, frame):
    remove_termux_notification()
    if PID_FILE.exists():
        try:
            PID_FILE.unlink()
        except Exception:
            pass
    sys.exit(0)

if __name__ == "__main__":
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, cleanup_and_exit)
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        cleanup_and_exit(None, None)

