#!/usr/bin/env python3
"""
Termux Bridge Daemon
High-performance WebSocket & HTTP bridge for controlling Termux from the Android Agent App.
Supports live streaming of stdout/stderr, process interruption, and auth tokens.
"""

import asyncio
import json
import os
import signal
import sys
import subprocess
import shutil
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
HOST = os.environ.get("BRIDGE_HOST", "127.0.0.1")
TOKEN_FILE = Path.home() / ".termux_agent_token"
DEFAULT_CWD = os.environ.get("HOME", os.path.expanduser("~"))

current_process = None
current_cwd = DEFAULT_CWD

def load_or_generate_token() -> str:
    """Load existing auth token or create a secure random token."""
    if TOKEN_FILE.exists():
        try:
            return TOKEN_FILE.read_text().strip()
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
    """Collect system and battery status via termux-api or standard Linux tools."""
    info = {
        "os": sys.platform,
        "cwd": current_cwd,
        "python_version": sys.version.split()[0],
        "has_termux_api": shutil.which("termux-battery-status") is not None,
        "has_llama": shutil.which("llama-server") is not None,
    }
    # Check battery if termux-api is available
    if info["has_termux_api"]:
        try:
            res = subprocess.run(["termux-battery-status"], capture_output=True, text=True, timeout=2)
            if res.returncode == 0:
                info["battery"] = json.loads(res.stdout)
        except Exception:
            pass
    return info

async def run_command_streaming(websocket, cmd: str, execution_id: str, cwd: str | None = None):
    """Executes a command and streams stdout/stderr chunks in real-time over WebSocket."""
    global current_process, current_cwd
    target_cwd = cwd or current_cwd

    # Handle internal 'cd' commands directly
    if cmd.strip().startswith("cd "):
        new_dir = cmd.strip()[3:].strip()
        expanded = os.path.expanduser(new_dir)
        full_path = os.path.normpath(os.path.join(target_cwd, expanded))
        if os.path.isdir(full_path):
            current_cwd = full_path
            await websocket.send(json.dumps({
                "type": "stdout",
                "execution_id": execution_id,
                "data": f"Changed directory to {current_cwd}\n"
            }))
            await websocket.send(json.dumps({
                "type": "completed",
                "execution_id": execution_id,
                "exit_code": 0,
                "cwd": current_cwd
            }))
            return
        else:
            await websocket.send(json.dumps({
                "type": "stderr",
                "execution_id": execution_id,
                "data": f"Directory not found: {new_dir}\n"
            }))
            await websocket.send(json.dumps({
                "type": "completed",
                "execution_id": execution_id,
                "exit_code": 1,
                "cwd": current_cwd
            }))
            return

    # Use bash shell if available, otherwise default system shell
    shell_bin = "/data/data/com.termux/files/usr/bin/bash"
    if not os.path.exists(shell_bin):
        shell_bin = shutil.which("bash") or shutil.which("sh") or "sh"

    try:
        current_process = await asyncio.create_subprocess_shell(
            cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            cwd=target_cwd,
            executable=shell_bin if sys.platform != "win32" else None,
            preexec_fn=os.setsid if sys.platform != "win32" else None
        )

        async def read_stream(stream, msg_type: str):
            while True:
                line = await stream.readline()
                if not line:
                    break
                decoded = line.decode("utf-8", errors="replace")
                await websocket.send(json.dumps({
                    "type": msg_type,
                    "execution_id": execution_id,
                    "data": decoded
                }))

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
                await websocket.send(json.dumps({"type": "error", "error": "Invalid JSON format"}))
                continue

            action = msg.get("action")

            # 1. Authentication handshake
            if action == "auth":
                provided_token = msg.get("token", "")
                if provided_token == AUTH_TOKEN:
                    authenticated = True
                    await websocket.send(json.dumps({
                        "type": "auth_ok",
                        "system": get_system_info(),
                        "cwd": current_cwd
                    }))
                else:
                    await websocket.send(json.dumps({
                        "type": "auth_fail",
                        "error": "Authentication failed: invalid token"
                    }))
                continue

            if not authenticated:
                await websocket.send(json.dumps({
                    "type": "error",
                    "error": "Not authenticated. Send {'action': 'auth', 'token': '...'} first."
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
                        "error": "Empty command"
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
                            "error": f"Failed to interrupt: {e}"
                        }))
                else:
                    await websocket.send(json.dumps({
                        "type": "info",
                        "message": "No active process to interrupt"
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

async def main():
    print(f"==================================================")
    print(f" AMC - AI Mobile Center Bridge Daemon")
    print(f" Listening on ws://{HOST}:{PORT}")
    print(f" Auth Token: {AUTH_TOKEN}")
    print(f" Token File: {TOKEN_FILE}")
    print(f"==================================================")

    async with websockets.serve(handle_connection, HOST, PORT):
        await asyncio.Future()  # run forever

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[INFO] Daemon stopped by user.")
