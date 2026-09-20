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
import time
import secrets
import hmac
import base64
import codecs
import stat
from contextlib import suppress
from pathlib import Path

# Install dependencies with setup.sh before starting the daemon.
import websockets

PORT = int(os.environ.get("BRIDGE_PORT", 8765))
HOST = "127.0.0.1"  # Device-local only; authentication is still mandatory.
TOKEN_FILE = Path.home() / ".termux_agent_token"
AGENT_DIR = Path.home() / ".termux_agent"
PID_FILE = AGENT_DIR / "daemon.pid"
DEFAULT_CWD = os.environ.get("HOME", os.path.expanduser("~"))


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
            subprocess.run([wake_lock], env=ENV, timeout=2, capture_output=True)
        except Exception:
            pass

def release_wake_lock():
    """Ensure Termux CPU wake lock is released on daemon shutdown."""
    wake_unlock = shutil.which("termux-wake-unlock", path=ENV.get("PATH"))
    if wake_unlock:
        try:
            subprocess.run([wake_unlock], env=ENV, timeout=2, capture_output=True)
        except Exception:
            pass

def update_termux_notification(status_text="Bridge active • Port 8765"):
    """Post or update an ongoing notification via termux-api to prevent background freeze."""
    termux_notif = shutil.which("termux-notification", path=ENV.get("PATH"))
    if termux_notif:
        try:
            subprocess.run([
                termux_notif,
                "--id", "amc_bridge",
                "--title", "AMC Termux Bridge (Active)",
                "--content", status_text,
                "--ongoing",
                "--priority", "high"
            ], env=ENV, timeout=2, capture_output=True)
        except Exception:
            pass

def remove_termux_notification():
    """Remove the ongoing Termux notification on shutdown."""
    termux_notif_rm = shutil.which("termux-notification-remove", path=ENV.get("PATH"))
    if termux_notif_rm:
        try:
            subprocess.run([termux_notif_rm, "amc_bridge"], env=ENV, timeout=2, capture_output=True)
        except Exception:
            pass

def load_or_generate_token() -> str:
    """Load existing auth token or create a secure random token."""
    if TOKEN_FILE.exists():
        TOKEN_FILE.chmod(0o600)
        token = TOKEN_FILE.read_text().strip()
        if token:
            return token
    token = secrets.token_hex(32)
    fd = os.open(TOKEN_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as token_file:
        token_file.write(token)
    TOKEN_FILE.chmod(0o600)
    return token

AUTH_TOKEN = load_or_generate_token()

_cached_battery = None
_last_battery_check = 0.0
_termux_api_available = True
_termux_api_consecutive_failures = 0
_termux_api_retry_after = 0.0

def get_battery_info_fast() -> dict | None:
    """Quickly read Android battery capacity and status from kernel sysfs without blocking."""
    try:
        cap_file = Path("/sys/class/power_supply/battery/capacity")
        if cap_file.exists():
            pct = int(cap_file.read_text().strip())
            stat_file = Path("/sys/class/power_supply/battery/status")
            status = stat_file.read_text().strip().upper() if stat_file.exists() else "UNKNOWN"
            return {
                "percentage": pct,
                "plugged": "AC" if status in ("CHARGING", "FULL") else "UNPLUGGED",
                "status": status,
                "health": "GOOD"
            }
    except Exception:
        pass
    return None

def get_system_info(cwd: str = DEFAULT_CWD) -> dict:
    """Collect system, memory, storage, and battery status via fast sysfs or termux-api."""
    global _cached_battery, _last_battery_check, _termux_api_available
    global _termux_api_consecutive_failures, _termux_api_retry_after
    info = {
        "os": sys.platform,
        "cwd": cwd,
        "python_version": sys.version.split()[0],
        "has_termux_api": shutil.which("termux-battery-status", path=ENV.get("PATH")) is not None,
        "has_llama": shutil.which("llama-server", path=ENV.get("PATH")) is not None,
    }

    # 1. First try fast kernel sysfs battery read (0.1ms, non-blocking)
    fast_batt = get_battery_info_fast()
    if fast_batt is not None:
        info["battery"] = fast_batt
        _cached_battery = fast_batt
        _termux_api_consecutive_failures = 0
        _termux_api_retry_after = 0.0
        _termux_api_available = True
    else:
        # 2. Fallback to cached battery or termux-battery-status with short 1.5s timeout
        now = time.time()
        if info["has_termux_api"] and (now >= _termux_api_retry_after):
            if _cached_battery is not None and (now - _last_battery_check < 30.0):
                info["battery"] = _cached_battery
            else:
                try:
                    res = subprocess.run(["termux-battery-status"], capture_output=True, text=True, timeout=1.5, env=ENV)
                    if res.returncode == 0 and res.stdout.strip():
                        _cached_battery = json.loads(res.stdout)
                        _last_battery_check = now
                        _termux_api_consecutive_failures = 0
                        _termux_api_retry_after = 0.0
                        _termux_api_available = True
                        info["battery"] = _cached_battery
                    else:
                        _termux_api_consecutive_failures += 1
                        _termux_api_retry_after = now + min(120.0, 15.0 * _termux_api_consecutive_failures)
                        _termux_api_available = False
                        if _cached_battery:
                            info["battery"] = _cached_battery
                except Exception:
                    _termux_api_consecutive_failures += 1
                    _termux_api_retry_after = now + min(120.0, 15.0 * _termux_api_consecutive_failures)
                    _termux_api_available = False
                    if _cached_battery:
                        info["battery"] = _cached_battery
        elif _cached_battery:
            info["battery"] = _cached_battery

    # Disk usage in HOME
    try:
        total, used, free = shutil.disk_usage(cwd)
        info["disk"] = {
            "total_mb": total // (1024 * 1024),
            "free_mb": free // (1024 * 1024),
            "used_mb": used // (1024 * 1024)
        }
    except Exception:
        pass

    return info

def validate_file_path(target_path_str: str, base_cwd: str = DEFAULT_CWD, for_write: bool = True) -> tuple[Path, str | None]:
    """
    Validate target path against path traversal, sensitive configuration files, and system directories.
    Returns a tuple of (resolved_path, error_message).
    """
    if not target_path_str or not target_path_str.strip():
        return Path(), "A file path is required."

    # Guard against shell command injection and control characters in file paths
    forbidden_chars = {";", "&", "|", "`", "$", "\n", "\r", "\0"}
    if any(ch in target_path_str for ch in forbidden_chars):
        return Path(), "Security block: File path contains forbidden shell metacharacters or control characters."

    expanded = os.path.expanduser(target_path_str.strip())
    if not os.path.isabs(expanded):
        target = Path(base_cwd) / expanded
    else:
        target = Path(expanded)

    try:
        resolved = target.resolve()
    except Exception as e:
        return Path(), f"Invalid path: {e}"

    home = Path(DEFAULT_CWD).resolve()

    if for_write:
        # Prevent writing outside HOME or allowed Android storage directories
        allowed_roots = [home]
        for candidate_str in ("/storage/emulated/0", "/sdcard", "/storage/self/primary"):
            candidate = Path(candidate_str)
            try:
                allowed_roots.append(candidate.resolve())
            except Exception:
                pass
            allowed_roots.append(candidate)

        # Allow targets of valid symlinks configured in ~/storage/ (e.g. termux-setup-storage)
        storage_dir = home / "storage"
        if storage_dir.is_dir():
            try:
                allowed_roots.append(storage_dir.resolve())
            except Exception:
                pass
            try:
                for entry in storage_dir.iterdir():
                    if entry.is_symlink():
                        try:
                            sym_target = entry.resolve()
                            if sym_target not in (Path("/"), Path("/etc"), Path("/proc"), Path("/sys")):
                                allowed_roots.append(sym_target)
                        except Exception:
                            pass
            except Exception:
                pass

        in_allowed_root = False
        for root in allowed_roots:
            try:
                resolved.relative_to(root)
                in_allowed_root = True
                break
            except ValueError:
                pass

        if not in_allowed_root:
            return resolved, f"Security block: Writing outside home directory ({home}) is forbidden."

        # Prevent modification of shell initialization & profile files
        sensitive_filenames = {
            ".bashrc", ".bash_profile", ".bash_login", ".bash_logout",
            ".profile", ".zshrc", ".zprofile", ".zshenv", ".zlogin", ".zlogout",
            ".login", ".cshrc", ".tcshrc", ".kshrc",
            ".termux_agent_token"
        }
        if resolved.name.lower() in sensitive_filenames:
            return resolved, f"Security block: Modifying sensitive configuration file '{resolved.name}' is forbidden."

        # Prevent modification inside critical directories
        sensitive_dirs = {".ssh", ".termux", ".termux_agent", ".gnupg"}
        for part in resolved.parts:
            if part.lower() in sensitive_dirs:
                return resolved, f"Security block: Modifying files in sensitive directory '{part}' is forbidden."
    else:
        # Prevent reading sensitive key material directly via bridge
        sensitive_read_dirs = {".ssh", ".gnupg"}
        for part in resolved.parts:
            if part.lower() in sensitive_read_dirs:
                return resolved, f"Security block: Reading files in sensitive directory '{part}' is forbidden."

    return resolved, None

def cleanup_process_resources(process):
    """Cleanly close subprocess transports and pipe handles to avoid ResourceWarnings and leaks."""
    if process is None:
        return
    for stream in (getattr(process, "stdin", None), getattr(process, "stdout", None), getattr(process, "stderr", None)):
        if stream is not None:
            transport = getattr(stream, "_transport", None)
            if transport is not None and hasattr(transport, "close") and not transport.is_closing():
                try:
                    transport.close()
                except Exception:
                    pass
    transport = getattr(process, "_transport", None)
    if transport is not None and hasattr(transport, "close") and not transport.is_closing():
        try:
            transport.close()
        except Exception:
            pass

RECONNECT_GRACE_SECONDS = float(os.environ.get("BRIDGE_RECONNECT_GRACE", 30.0))
DETACHED_SESSIONS: dict[str, "BridgeSession"] = {}

def register_detached_session(session: "BridgeSession"):
    """Register a detached session during reconnect grace window."""
    DETACHED_SESSIONS[session.session_id] = session
    if session.execution_id:
        DETACHED_SESSIONS[session.execution_id] = session

def unregister_detached_session(session: "BridgeSession"):
    """Remove all references to session from DETACHED_SESSIONS."""
    for k in list(DETACHED_SESSIONS.keys()):
        if DETACHED_SESSIONS[k] is session:
            del DETACHED_SESSIONS[k]

def pop_detached_session(execution_id: str | None = None) -> "BridgeSession | None":
    """Retrieve and unregister a detached session by execution_id or most recent."""
    matched = None
    if execution_id:
        for k, sess in list(DETACHED_SESSIONS.items()):
            if k == execution_id or getattr(sess, "execution_id", None) == execution_id:
                matched = sess
                break
    elif DETACHED_SESSIONS:
        matched = next(reversed(list(DETACHED_SESSIONS.values())))
    if matched is not None:
        unregister_detached_session(matched)
        return matched
    return None

class BridgeSession:
    """Own execution state per authenticated connection."""

    def __init__(self, websocket):
        self.session_id = secrets.token_hex(8)
        self.websocket = websocket
        self.cwd = DEFAULT_CWD
        self.task = None
        self.process = None
        self.execution_id = None
        self.buffered_output = []
        self.buffered_events = []
        self.grace_task = None
        self.file_tasks = set()

    def is_running(self) -> bool:
        return self.task is not None and not self.task.done()

    async def attach(self, websocket):
        """Re-attach a newly connected WebSocket to an existing running session."""
        if self.grace_task is not None and not self.grace_task.done():
            self.grace_task.cancel()
            self.grace_task = None
        self.websocket = websocket
        await self.flush_buffered()

    async def flush_buffered(self):
        """Flush output and events buffered while disconnected."""
        if self.websocket is None:
            return
        for item in self.buffered_output:
            try:
                await self.websocket.send(json.dumps(item))
            except websockets.exceptions.ConnectionClosed:
                self.websocket = None
                return
        self.buffered_output.clear()

        for item in self.buffered_events:
            try:
                await self.websocket.send(json.dumps(item))
            except websockets.exceptions.ConnectionClosed:
                self.websocket = None
                return
        self.buffered_events.clear()

    async def wait_grace_period(self, duration: float):
        """Wait for reconnect; if grace period expires without reconnection, stop execution."""
        try:
            await asyncio.sleep(duration)
            await self.stop()
        except asyncio.CancelledError:
            pass
        finally:
            unregister_detached_session(self)

    async def send(self, kind, **payload):
        msg = json.dumps({"type": kind, **payload})
        if self.websocket is not None:
            try:
                await self.websocket.send(msg)
                return
            except websockets.exceptions.ConnectionClosed:
                self.websocket = None
        if kind in ("completed", "interrupted", "error"):
            self.buffered_events.append({"type": kind, **payload})
        elif kind in ("stdout", "stderr"):
            self.buffered_output.append({"type": kind, **payload})
            if len(self.buffered_output) > 200:
                self.buffered_output.pop(0)

    async def terminate_process(self):
        process = self.process
        if process is None:
            return
        if sys.platform == "win32":
            try:
                if process.returncode is None:
                    process.kill()
            except ProcessLookupError:
                pass
            with suppress(asyncio.TimeoutError):
                await asyncio.wait_for(process.wait(), timeout=2)
            cleanup_process_resources(process)
            return

        # Signal the entire group, even if its shell has already exited.
        sigkill = getattr(signal, "SIGKILL", signal.SIGTERM)
        for sig in (signal.SIGINT, signal.SIGTERM, sigkill):
            try:
                os.killpg(process.pid, sig)
            except ProcessLookupError:
                break
            if sig != sigkill:
                await asyncio.sleep(0.25)
                if process.returncode is not None:
                    break
        with suppress(asyncio.TimeoutError):
            await asyncio.wait_for(process.wait(), timeout=2)
        cleanup_process_resources(process)

    async def stop(self):
        curr = asyncio.current_task()
        if self.grace_task is not None and self.grace_task != curr and not self.grace_task.done():
            self.grace_task.cancel()
            self.grace_task = None
        task = self.task
        if task is not None and not task.done():
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task
        self.task = None
        if self.process is not None:
            await self.terminate_process()
            cleanup_process_resources(self.process)
            self.process = None
        for ft in list(self.file_tasks):
            if not ft.done():
                ft.cancel()
        if self.file_tasks:
            await asyncio.gather(*self.file_tasks, return_exceptions=True)
            self.file_tasks.clear()
        self.buffered_output.clear()
        self.buffered_events.clear()
        unregister_detached_session(self)

    async def run(self, command, execution_id, cwd, timeout_seconds):
        readers = []
        try:
            trimmed = command.strip()
            if trimmed.startswith("cd ") or trimmed == "cd":
                try:
                    tokens = shlex.split(trimmed)
                    # Only intercept a plain cd; let the shell handle compound commands.
                    if tokens and tokens[0] == "cd" and len(tokens) <= 2 and not any(
                        char in trimmed for char in ";&|<>\n\x60"
                    ):
                        raw_target = tokens[1] if len(tokens) == 2 else DEFAULT_CWD
                        target = os.path.expandvars(os.path.expanduser(raw_target))
                        target = os.path.abspath(os.path.join(cwd, target))
                        if not os.path.isdir(target):
                            raise ValueError(f"Directory not found: {target}")
                        self.cwd = target
                        await self.send("stdout", execution_id=execution_id, data=f"Directory changed to: {target}\n")
                        await self.send("completed", execution_id=execution_id, exit_code=0, cwd=self.cwd)
                        return
                except ValueError as err:
                    if str(err).startswith("Directory not found:"):
                        raise
                    pass

            shell = shutil.which("bash", path=ENV.get("PATH")) or shutil.which("sh") or "sh"
            # Shield spawn so cancellation cannot orphan a process created concurrently.
            spawning = asyncio.create_task(asyncio.create_subprocess_shell(
                command, stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
                cwd=cwd, executable=shell if sys.platform != "win32" else None,
                env=ENV, start_new_session=sys.platform != "win32"
            ))
            try:
                self.process = await asyncio.shield(spawning)
            except asyncio.CancelledError:
                self.process = await spawning
                raise

            async def stream(reader, kind):
                decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
                while True:
                    chunk = await reader.read(4096)
                    if not chunk:
                        tail = decoder.decode(b"", final=True)
                        if tail:
                            await self.send(kind, execution_id=execution_id, data=tail)
                        break
                    await self.send(kind, execution_id=execution_id, data=decoder.decode(chunk))

            readers = [
                asyncio.create_task(stream(self.process.stdout, "stdout")),
                asyncio.create_task(stream(self.process.stderr, "stderr"))
            ]
            try:
                await asyncio.wait_for(asyncio.gather(*readers, self.process.wait()), timeout_seconds)
            except asyncio.TimeoutError:
                await self.terminate_process()
                await self.send("error", execution_id=execution_id, error="Command timed out and was stopped.")
                return
            await self.send("completed", execution_id=execution_id,
                            exit_code=self.process.returncode, cwd=self.cwd)
        except asyncio.CancelledError:
            await self.terminate_process()
            raise
        except Exception as error:
            await self.terminate_process()
            with suppress(websockets.exceptions.ConnectionClosed):
                await self.send("error", execution_id=execution_id, error=str(error))
        finally:
            for reader in readers:
                if not reader.done():
                    reader.cancel()
            if readers:
                await asyncio.gather(*readers, return_exceptions=True)
            if self.process is not None:
                cleanup_process_resources(self.process)
            self.process = None
            self.execution_id = None

    async def transfer_file(self, action, msg):
        request_path = msg.get("path", "")
        request_id = msg.get("request_id")
        extra = {"request_id": request_id} if request_id is not None else {}
        for_write = (action == "write_file")
        try:
            resolved_path, err = validate_file_path(request_path, self.cwd, for_write=for_write)
            if err:
                with suppress(websockets.exceptions.ConnectionClosed):
                    await self.send("error", path=request_path, error=err, **extra)
                return

            path = str(resolved_path)
            if action == "write_file":
                content = msg.get("content", "")
                if not isinstance(content, str):
                    raise ValueError("File content must be a string.")
                def write():
                    if os.path.exists(path) and not stat.S_ISREG(os.stat(path).st_mode):
                        raise ValueError("Target exists and is not a regular file.")
                    os.makedirs(os.path.dirname(path), exist_ok=True)
                    Path(path).write_text(content, encoding="utf-8")
                await asyncio.to_thread(write)
                with suppress(websockets.exceptions.ConnectionClosed):
                    await self.send("file_written", path=request_path, success=True, **extra)
            else:
                def read():
                    if not os.path.exists(path):
                        raise FileNotFoundError(f"No such file: {request_path}")
                    st = os.stat(path)
                    if not stat.S_ISREG(st.st_mode):
                        raise ValueError(f"Path is not a regular file: {request_path}")
                    flags = os.O_RDONLY
                    if hasattr(os, "O_NONBLOCK"):
                        flags |= os.O_NONBLOCK
                    fd = os.open(path, flags)
                    try:
                        f_st = os.fstat(fd)
                        if not stat.S_ISREG(f_st.st_mode):
                            raise ValueError(f"Path is not a regular file: {request_path}")
                        with os.fdopen(fd, "rb") as source:
                            data = source.read(5 * 1024 * 1024 + 1)
                            fd = None
                    finally:
                        if fd is not None:
                            os.close(fd)
                    if len(data) > 5 * 1024 * 1024:
                        raise ValueError("File exceeds the 5 MiB transfer limit.")
                    return data
                data = await asyncio.to_thread(read)
                with suppress(websockets.exceptions.ConnectionClosed):
                    if action == "read_file_base64":
                        await self.send("file_base64", path=request_path, data=base64.b64encode(data).decode("ascii"), **extra)
                    else:
                        await self.send("file_content", path=request_path, content=data.decode("utf-8", errors="replace"), **extra)
        except asyncio.CancelledError:
            pass
        except Exception as error:
            with suppress(websockets.exceptions.ConnectionClosed):
                await self.send("error", path=request_path, error=str(error), **extra)


async def handle_connection(websocket):
    """Require a token for every client; receive controls while commands run."""
    session = None
    authenticated = False
    try:
        async for message in websocket:
            try:
                msg = json.loads(message)
                if not isinstance(msg, dict):
                    raise ValueError("Expected a JSON object.")
            except (ValueError, TypeError):
                if session is not None:
                    await session.send("error", error="Invalid JSON object.")
                else:
                    with suppress(websockets.exceptions.ConnectionClosed):
                        await websocket.send(json.dumps({"type": "error", "error": "Invalid JSON object."}))
                continue
            action = msg.get("action")
            if action == "auth":
                token = msg.get("token")
                if isinstance(token, str) and AUTH_TOKEN and hmac.compare_digest(
                    token.encode("utf-8"), AUTH_TOKEN.encode("utf-8")
                ):
                    authenticated = True
                    matched_session = pop_detached_session(msg.get("execution_id"))
                    if matched_session is not None:
                        session = matched_session
                        await session.attach(websocket)
                    else:
                        session = BridgeSession(websocket)
                    info = await asyncio.to_thread(get_system_info, session.cwd)
                    await session.send("auth_ok", system=info, cwd=session.cwd)
                else:
                    err_msg = json.dumps({"type": "auth_fail", "error": "Authentication failed: invalid token."})
                    with suppress(websockets.exceptions.ConnectionClosed):
                        await websocket.send(err_msg)
                    await websocket.close(code=1008, reason="Authentication required")
                    return
                continue
            if not authenticated or session is None:
                err_payload = {"type": "error", "error": "Authentication required."}
                if msg.get("execution_id"):
                    err_payload["execution_id"] = msg.get("execution_id")
                with suppress(websockets.exceptions.ConnectionClosed):
                    await websocket.send(json.dumps(err_payload))
                continue
            if action == "ping":
                resp = {"timestamp": msg.get("timestamp", 0)}
                if "ping_id" in msg:
                    resp["ping_id"] = msg["ping_id"]
                await session.send("pong", **resp)
            elif action == "sys_info":
                sys_data = await asyncio.to_thread(get_system_info, session.cwd)
                await session.send("sys_info", data=sys_data, system=sys_data, cwd=session.cwd)
            elif action == "execute":
                command = msg.get("command")
                raw_exec_id = msg.get("execution_id")
                execution_id = str(raw_exec_id) if raw_exec_id is not None and str(raw_exec_id).strip() else "default"
                if not isinstance(command, str) or not command.strip():
                    await session.send("error", execution_id=execution_id, error="Command must be a nonempty string.")
                    continue
                if session.task is not None and not session.task.done():
                    await session.send("error", execution_id=execution_id, error="A command is already running on this connection.")
                    continue
                raw_cwd = msg.get("cwd")
                cwd = raw_cwd if (isinstance(raw_cwd, str) and raw_cwd.strip()) else session.cwd
                if not isinstance(cwd, str):
                    await session.send("error", execution_id=execution_id, error="Invalid working directory.")
                    continue
                timeout_ms = msg.get("timeout_ms")
                if timeout_ms is None:
                    timeout_ms = 90000
                elif not isinstance(timeout_ms, (int, float)) or not 1 <= timeout_ms <= 3600000:
                    await session.send("error", execution_id=execution_id, error="Invalid command timeout.")
                    continue
                session.execution_id = execution_id
                session.task = asyncio.create_task(session.run(command.strip(), execution_id, cwd, timeout_ms / 1000))
            elif action == "interrupt":
                execution_id = session.execution_id
                requested = msg.get("execution_id")
                if requested and requested != execution_id:
                    await session.send("error", execution_id=requested, error="Execution is no longer active.")
                    continue
                await session.stop()
                await session.send("interrupted", execution_id=execution_id or "")
                if execution_id:
                    await session.send("completed", execution_id=execution_id, exit_code=130, cwd=session.cwd)
            elif action in ("read_file", "read_file_base64", "write_file"):
                request_id = msg.get("request_id")
                extra = {"request_id": request_id} if request_id is not None else {}
                if not isinstance(msg.get("path"), str) or not msg["path"]:
                    await session.send("error", error="A file path is required.", **extra)
                    continue
                ftask = asyncio.create_task(session.transfer_file(action, msg))
                session.file_tasks.add(ftask)
                ftask.add_done_callback(session.file_tasks.discard)
            else:
                await session.send("error", error="Unknown action.")
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        if session is not None:
            session.websocket = None
            if session.is_running():
                register_detached_session(session)
                session.grace_task = asyncio.create_task(
                    session.wait_grace_period(RECONNECT_GRACE_SECONDS)
                )
            else:
                await session.stop()


def _watchdog_sync_cycle():
    """Run wake-lock and notification update in background thread so event loop is never blocked."""
    try:
        ensure_wake_lock()
        info = get_system_info()
        batt = info.get("battery", {})
        pct = batt.get("percentage")
        status_text = f"Port {PORT} • Battery: {pct}%" if pct is not None else f"Port {PORT} • Background active"
        update_termux_notification(status_text)
    except Exception:
        pass

async def background_watchdog():
    """Background task running alongside the websocket server to keep Termux alive."""
    while True:
        try:
            if hasattr(asyncio, "to_thread"):
                await asyncio.to_thread(_watchdog_sync_cycle)
            else:
                loop = asyncio.get_running_loop()
                await loop.run_in_executor(None, _watchdog_sync_cycle)
        except Exception:
            pass
        await asyncio.sleep(45)

async def main():
    # Ignore SIGHUP and SIGPIPE so closing terminal or broken pipes do not terminate the daemon
    if hasattr(signal, "SIGHUP"):
        try:
            signal.signal(signal.SIGHUP, signal.SIG_IGN)
        except Exception:
            pass
    if hasattr(signal, "SIGPIPE"):
        try:
            signal.signal(signal.SIGPIPE, signal.SIG_IGN)
        except Exception:
            pass

    stop_event = asyncio.Event()
    loop = asyncio.get_running_loop()
    for stop_signal in (signal.SIGTERM, signal.SIGINT):
        try:
            loop.add_signal_handler(stop_signal, stop_event.set)
        except (NotImplementedError, AttributeError):
            pass

    # Acquire the listening socket before publishing service state. A duplicate
    # start must never overwrite or remove the running instance's PID file.
    async with websockets.serve(
        handle_connection, HOST, PORT, max_size=10 * 1024 * 1024,
        origins=[None], ping_interval=None, ping_timeout=None
    ):
        AGENT_DIR.mkdir(parents=True, exist_ok=True)
        PID_FILE.write_text(str(os.getpid()))
        watchdog_task = None
        try:
            ensure_wake_lock()
            update_termux_notification("AMC Bridge started • Port " + str(PORT))
            print(f"AMC Bridge listening on ws://{HOST}:{PORT}; PID: {os.getpid()}")
            print("Authentication required. Use 'amc token' to pair the app.")
            watchdog_task = asyncio.create_task(background_watchdog())
            await stop_event.wait()
        finally:
            if watchdog_task is not None:
                watchdog_task.cancel()
                with suppress(asyncio.CancelledError):
                    await watchdog_task
            for s in list(DETACHED_SESSIONS.values()):
                await s.stop()
            DETACHED_SESSIONS.clear()
            remove_termux_notification()
            release_wake_lock()
            remove_owned_pid_file()


def remove_owned_pid_file():
    """Only remove service state published by this process."""
    try:
        if PID_FILE.read_text().strip() == str(os.getpid()):
            PID_FILE.unlink()
    except FileNotFoundError:
        pass


def cleanup_and_exit(signum, frame):
    remove_termux_notification()
    release_wake_lock()
    remove_owned_pid_file()
    sys.exit(0)

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        cleanup_and_exit(None, None)

