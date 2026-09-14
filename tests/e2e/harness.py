"""
Aimovix-AMC E2E Test Harness.
Provides ephemeral daemon process supervision, opaque-box WebSocket clients,
and simulated Android client components for end-to-end verification.
"""

import asyncio
import base64
import json
import os
import pathlib
import random
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from typing import Any, Dict, List, Optional, Tuple

import websockets

PROJECT_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
BRIDGE_DAEMON_PATH = PROJECT_ROOT / "termux-bridge" / "bridge_daemon.py"


def find_free_port() -> int:
    """Find and return an unused TCP port on loopback."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        s.listen(1)
        return s.getsockname()[1]


def make_python_cmd(*args: str) -> str:
    """Format cross-platform shell command invoking current python interpreter."""
    if sys.platform == "win32":
        return subprocess.list2cmdline([sys.executable, *args])
    import shlex
    return shlex.quote(sys.executable) + " " + " ".join(shlex.quote(a) for a in args)


class HeadTailBuffer:
    """Python reference implementation of Android's HeadTailBuffer (100KB head, 150KB tail)."""

    def __init__(self, max_head: int = 100 * 1024, max_tail: int = 150 * 1024):
        self.max_head = max_head
        self.max_tail = max_tail
        self.head: List[str] = []
        self.tail: List[str] = []
        self.head_len = 0
        self.tail_len = 0
        self.total_omitted = 0

    def append(self, chunk: str) -> None:
        rem_head = self.max_head - self.head_len
        if rem_head > 0:
            to_head = chunk[:rem_head]
            self.head.append(to_head)
            self.head_len += len(to_head)
            rem_chunk = chunk[rem_head:]
            if rem_chunk:
                self._append_to_tail(rem_chunk)
        else:
            self._append_to_tail(chunk)

    def _append_to_tail(self, chunk: str) -> None:
        self.tail.append(chunk)
        self.tail_len += len(chunk)
        if self.tail_len > self.max_tail:
            overflow = self.tail_len - self.max_tail
            self.total_omitted += overflow
            # Trim from beginning of tail
            joined = "".join(self.tail)
            trimmed = joined[overflow:]
            self.tail = [trimmed]
            self.tail_len = len(trimmed)

    def build(self) -> str:
        head_str = "".join(self.head)
        tail_str = "".join(self.tail)
        if self.total_omitted > 0:
            return f"{head_str}\n\n[... {self.total_omitted} characters omitted ...]\n\n{tail_str}"
        return f"{head_str}{tail_str}"


class BackoffCalculator:
    """Android client exponential backoff policy simulation with jitter (1s .. 30s cap)."""

    MIN_DELAY_MS = 1000
    MAX_DELAY_MS = 30000

    @classmethod
    def calculate_delay_ms(cls, attempt: int, apply_jitter: bool = True) -> int:
        if attempt <= 0:
            base = cls.MIN_DELAY_MS
        else:
            base = min(cls.MAX_DELAY_MS, cls.MIN_DELAY_MS * (2 ** min(attempt, 6)))
        if not apply_jitter:
            return base
        # Apply jitter: +/- 15%
        jitter_factor = random.uniform(0.85, 1.15)
        return int(min(cls.MAX_DELAY_MS, max(cls.MIN_DELAY_MS, base * jitter_factor)))


class DaemonProcess:
    """Supervises an isolated bridge daemon subprocess."""

    def __init__(self, port: Optional[int] = None, home_dir: Optional[str] = None):
        self.port = port or find_free_port()
        self._temp_dir = tempfile.TemporaryDirectory(ignore_cleanup_errors=True) if home_dir is None else None
        self.home_dir = home_dir or self._temp_dir.name
        self.process: Optional[subprocess.Popen] = None
        self.token: str = ""
        self.url = f"ws://127.0.0.1:{self.port}"
        self.pid: Optional[int] = None

    def start(self, startup_timeout: float = 5.0) -> "DaemonProcess":
        env = os.environ.copy()
        env["HOME"] = self.home_dir
        env["USERPROFILE"] = self.home_dir
        env["BRIDGE_PORT"] = str(self.port)

        self.process = subprocess.Popen(
            [sys.executable, str(BRIDGE_DAEMON_PATH)],
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.pid = self.process.pid

        # Wait for token file to be created
        token_path = pathlib.Path(self.home_dir) / ".termux_agent_token"
        deadline = time.time() + startup_timeout
        while time.time() < deadline:
            if self.process.poll() is not None:
                out, err = self.process.communicate()
                raise RuntimeError(f"Daemon exited prematurely ({self.process.returncode}):\n{out}\n{err}")
            if token_path.exists():
                token = token_path.read_text(encoding="utf-8").strip()
                if token:
                    self.token = token
                    break
            time.sleep(0.05)

        if not self.token:
            self.stop()
            raise TimeoutError(f"Daemon did not produce auth token within {startup_timeout}s")

        # Wait briefly for socket readiness
        ready = False
        while time.time() < deadline:
            try:
                with socket.create_connection(("127.0.0.1", self.port), timeout=0.2):
                    ready = True
                    break
            except (ConnectionRefusedError, OSError):
                time.sleep(0.05)

        if not ready:
            self.stop()
            raise TimeoutError(f"Daemon did not bind to port {self.port} within {startup_timeout}s")

        return self

    def stop(self) -> None:
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=2)
        if self._temp_dir is not None:
            self._temp_dir.cleanup()


class E2EWebSocketClient:
    """Opaque-box async WebSocket client for testing bridge protocol frames."""

    def __init__(self, url: str):
        self.url = url
        self.ws: Optional[websockets.WebSocketClientProtocol] = None
        self.authenticated = False
        self.last_auth_response: Optional[Dict[str, Any]] = None

    async def connect(self, origin: Optional[str] = None) -> "E2EWebSocketClient":
        # Pass origin if specified; default to None (direct non-browser socket)
        headers = {}
        if origin is not None:
            headers["Origin"] = origin
        self.ws = await websockets.connect(self.url, origin=origin, max_size=10 * 1024 * 1024)
        return self

    async def authenticate(self, token: str) -> Dict[str, Any]:
        await self.send("auth", token=token)
        resp = await self.recv()
        self.last_auth_response = resp
        if resp.get("type") == "auth_ok":
            self.authenticated = True
        return resp

    async def send(self, action: Optional[str] = None, raw_dict: Optional[Dict[str, Any]] = None, **kwargs) -> None:
        if raw_dict is not None:
            payload = raw_dict
        else:
            payload = {}
            if action is not None:
                payload["action"] = action
            payload.update(kwargs)
        if self.ws is not None:
            await self.ws.send(json.dumps(payload))

    async def send_raw_text(self, text: str) -> None:
        if self.ws is not None:
            await self.ws.send(text)

    async def recv(self, timeout: float = 5.0) -> Dict[str, Any]:
        if self.ws is None:
            raise RuntimeError("WebSocket is not connected")
        raw = await asyncio.wait_for(self.ws.recv(), timeout=timeout)
        return json.loads(raw)

    async def collect_until(self, end_type: str, timeout: float = 15.0) -> List[Dict[str, Any]]:
        """Collect all incoming frames until a message of type `end_type` arrives."""
        messages = []
        deadline = time.time() + timeout
        while time.time() < deadline:
            remaining = max(0.1, deadline - time.time())
            msg = await self.recv(timeout=remaining)
            messages.append(msg)
            if msg.get("type") == end_type:
                return messages
        raise TimeoutError(f"Timed out waiting for frame type: {end_type}")

    async def ping(self, ping_id: Optional[str] = None, timestamp: Optional[int] = None) -> Dict[str, Any]:
        payload: Dict[str, Any] = {"action": "ping", "timestamp": timestamp or int(time.time() * 1000)}
        if ping_id is not None:
            payload["ping_id"] = ping_id
        await self.send(raw_dict=payload)
        return await self.recv()

    async def execute(self, command: str, execution_id: str = "exec_1", cwd: Optional[str] = None, timeout_ms: int = 90000) -> Tuple[str, str, int]:
        """Execute command and collect full stdout, stderr, and exit_code."""
        payload: Dict[str, Any] = {
            "action": "execute",
            "command": command,
            "execution_id": execution_id,
            "timeout_ms": timeout_ms
        }
        if cwd is not None:
            payload["cwd"] = cwd
        await self.send(raw_dict=payload)

        stdout_chunks = []
        stderr_chunks = []
        exit_code = -1

        frames = await self.collect_until("completed")
        for f in frames:
            if f.get("type") == "stdout":
                stdout_chunks.append(f.get("data", ""))
            elif f.get("type") == "stderr":
                stderr_chunks.append(f.get("data", ""))
            elif f.get("type") == "completed":
                exit_code = f.get("exit_code", -1)

        return "".join(stdout_chunks), "".join(stderr_chunks), exit_code

    async def interrupt(self, execution_id: Optional[str] = None) -> List[Dict[str, Any]]:
        payload: Dict[str, Any] = {"action": "interrupt"}
        if execution_id is not None:
            payload["execution_id"] = execution_id
        await self.send(raw_dict=payload)
        return await self.collect_until("completed")

    async def write_file(self, path: str, content: str, request_id: Optional[str] = None) -> Dict[str, Any]:
        payload: Dict[str, Any] = {"action": "write_file", "path": path, "content": content}
        if request_id is not None:
            payload["request_id"] = request_id
        await self.send(raw_dict=payload)
        return await self.recv()

    async def read_file(self, path: str, request_id: Optional[str] = None) -> Dict[str, Any]:
        payload: Dict[str, Any] = {"action": "read_file", "path": path}
        if request_id is not None:
            payload["request_id"] = request_id
        await self.send(raw_dict=payload)
        return await self.recv()

    async def read_file_base64(self, path: str, request_id: Optional[str] = None) -> Dict[str, Any]:
        payload: Dict[str, Any] = {"action": "read_file_base64", "path": path}
        if request_id is not None:
            payload["request_id"] = request_id
        await self.send(raw_dict=payload)
        return await self.recv()

    async def sys_info(self) -> Dict[str, Any]:
        await self.send("sys_info")
        return await self.recv()

    async def close(self, code: int = 1000, reason: str = "Normal closure") -> None:
        if self.ws is not None:
            try:
                await self.ws.close(code=code, reason=reason)
            except Exception:
                pass
            self.ws = None


class BaseE2ETestCase(unittest.IsolatedAsyncioTestCase):
    """Base test case providing an isolated daemon instance and client cleanup."""

    daemon: DaemonProcess
    client: E2EWebSocketClient
    clients: List[E2EWebSocketClient]

    async def asyncSetUp(self):
        self.clients = []
        self.daemon = DaemonProcess().start()
        self.client = await self.create_client(authenticate=True)

    async def asyncTearDown(self):
        for c in self.clients:
            await c.close()
        self.daemon.stop()

    async def create_client(self, authenticate: bool = True) -> E2EWebSocketClient:
        c = E2EWebSocketClient(self.daemon.url)
        await c.connect()
        self.clients.append(c)
        if authenticate:
            auth_resp = await c.authenticate(self.daemon.token)
            self.assertEqual(auth_resp.get("type"), "auth_ok")
        return c
