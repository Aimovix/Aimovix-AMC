"""Integration tests for token authentication, process control, and connection isolation."""
import asyncio
import importlib.util
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import websockets

TEST_HOME = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
with patch.dict(os.environ, {"HOME": TEST_HOME.name}):
    spec = importlib.util.spec_from_file_location("bridge", Path(__file__).with_name("bridge_daemon.py"))
    bridge = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bridge)


def make_python_cmd(*args: str) -> str:
    """Format cross-platform command invoking the active python interpreter."""
    if sys.platform == "win32":
        return subprocess.list2cmdline([sys.executable, *args])
    return shlex.quote(sys.executable) + " " + " ".join(shlex.quote(a) for a in args)


class BridgeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.info_patch = patch.object(bridge, "get_system_info", lambda cwd=bridge.DEFAULT_CWD: {"cwd": cwd})
        self.info_patch.start()
        self.server = await websockets.serve(bridge.handle_connection, bridge.HOST, 0, origins=[None])
        self.url = f"ws://127.0.0.1:{self.server.sockets[0].getsockname()[1]}"
        self.clients = []

    async def asyncTearDown(self):
        for client in self.clients:
            await client.close()
        self.server.close()
        await self.server.wait_closed()
        self.info_patch.stop()

    async def connect(self, authenticate=True):
        client = await websockets.connect(self.url)
        self.clients.append(client)
        if authenticate:
            await client.send(json.dumps({"action": "auth", "token": bridge.AUTH_TOKEN}))
            self.assertEqual((await self.receive(client))["type"], "auth_ok")
        return client

    async def receive(self, client):
        return json.loads(await asyncio.wait_for(client.recv(), 5))

    async def until(self, client, kind):
        messages = []
        for _ in range(200):
            msg = await self.receive(client)
            messages.append(msg)
            if msg["type"] == kind:
                return messages
        self.fail(f"Did not receive {kind}")

    async def execute(self, client, command, execution_id="job", **extra):
        await client.send(json.dumps({
            "action": "execute", "command": command, "execution_id": execution_id, **extra
        }))

    async def test_loopback_requires_authentication_for_all_operations(self):
        client = await self.connect(False)
        for action in ("execute", "read_file", "write_file", "read_file_base64", "interrupt", "sys_info"):
            await client.send(json.dumps({"action": action, "command": "echo forbidden", "path": "secret"}))
            self.assertEqual((await self.receive(client))["error"], "Authentication required.")

    async def test_wrong_and_empty_tokens_are_rejected(self):
        for token in ("", "wrong-token", None):
            client = await self.connect(False)
            await client.send(json.dumps({"action": "auth", "token": token}))
            self.assertEqual((await self.receive(client))["type"], "auth_fail")

    async def test_browser_origins_are_rejected(self):
        with self.assertRaises(websockets.exceptions.InvalidHandshake):
            await websockets.connect(self.url, origin="https://example.com")

    async def test_no_auth_environment_cannot_disable_authentication(self):
        with patch.dict(os.environ, {"BRIDGE_NO_AUTH": "1"}), patch.object(sys, "argv", ["bridge", "--no-auth"]):
            self.assertTrue(bridge.load_or_generate_token())
        self.assertEqual(bridge.HOST, "127.0.0.1")
        if sys.platform != "win32":
            self.assertEqual(bridge.TOKEN_FILE.stat().st_mode & 0o777, 0o600)

    async def test_ping_and_interrupt_work_during_execution(self):
        client = await self.connect()
        cmd = make_python_cmd("-u", "-c", "import time; print('ready', flush=True); time.sleep(30)")
        await self.execute(client, cmd)
        self.assertIn("ready", (await self.receive(client))["data"])
        await client.send(json.dumps({"action": "ping", "timestamp": 123}))
        self.assertEqual((await self.receive(client))["type"], "pong")
        await client.send(json.dumps({"action": "interrupt"}))
        messages = await self.until(client, "completed")
        self.assertTrue(any(m["type"] == "interrupted" for m in messages))
        self.assertEqual(messages[-1]["exit_code"], 130)
        await self.execute(client, make_python_cmd("-c", "print('usable')"), execution_id="next")
        messages = await self.until(client, "completed")
        self.assertIn("usable", "".join(m.get("data", "") for m in messages))
        self.assertEqual(messages[-1]["exit_code"], 0)

    async def test_busy_execution_is_rejected_without_replacing_process(self):
        client = await self.connect()
        cmd = make_python_cmd("-u", "-c", "import time; print('ready', flush=True); time.sleep(30)")
        await self.execute(client, cmd)
        await self.receive(client)
        await self.execute(client, make_python_cmd("-c", "print('should-not-run')"), execution_id="second")
        message = await self.receive(client)
        self.assertEqual(message["type"], "error")
        self.assertEqual(message["execution_id"], "second")
        await client.send(json.dumps({"action": "interrupt"}))
        await self.until(client, "completed")

    async def test_connection_state_and_interrupts_are_isolated(self):
        first, second = await self.connect(), await self.connect()
        directory = Path(TEST_HOME.name) / "subdir"
        directory.mkdir(exist_ok=True)
        await self.execute(first, "cd " + shlex.quote(str(directory)))
        await self.until(first, "completed")
        cmd = make_python_cmd("-u", "-c", "import time; print('first', flush=True); time.sleep(30)")
        await self.execute(first, cmd)
        await self.receive(first)
        await second.send(json.dumps({"action": "interrupt"}))
        self.assertEqual((await self.receive(second))["type"], "interrupted")
        await self.execute(second, make_python_cmd("-c", "import os; print(os.getcwd())"))
        result = await self.until(second, "completed")
        output = "".join(m.get("data", "") for m in result).strip()
        self.assertEqual(os.path.normpath(output), os.path.normpath(TEST_HOME.name))
        await first.send(json.dumps({"action": "interrupt"}))
        result = await self.until(first, "completed")
        self.assertEqual(result[-1]["exit_code"], 130)

    async def test_timeout_stops_process(self):
        client = await self.connect()
        cmd = make_python_cmd("-c", "import time; time.sleep(30)")
        await self.execute(client, cmd, timeout_ms=50)
        result = await self.until(client, "error")
        self.assertIn("timed out", result[-1]["error"])
        await self.execute(client, make_python_cmd("-c", "print('after-timeout')"))
        self.assertEqual((await self.until(client, "completed"))[-1]["exit_code"], 0)

    async def test_long_output_without_newline_streams(self):
        client = await self.connect()
        command = make_python_cmd("-c", "import sys; sys.stdout.write('x' * 100000)")
        await self.execute(client, command)
        result = await self.until(client, "completed")
        self.assertEqual(len("".join(m.get("data", "") for m in result)), 100000)
        self.assertEqual(result[-1]["exit_code"], 0)

    async def test_relative_file_response_uses_requested_path(self):
        client = await self.connect()
        Path(TEST_HOME.name, "sample.txt").write_text("hello", encoding="utf-8")
        await client.send(json.dumps({"action": "read_file", "path": "sample.txt"}))
        self.assertEqual(await self.receive(client), {
            "type": "file_content", "path": "sample.txt", "content": "hello"
        })

    async def test_transfer_file_blocks_sensitive_files(self):
        client = await self.connect()
        # 1. Shell config files write protection
        for bad_path in (".bashrc", "~/.bashrc", ".profile", ".zshrc"):
            await client.send(json.dumps({"action": "write_file", "path": bad_path, "content": "malicious"}))
            resp = await self.receive(client)
            self.assertEqual(resp["type"], "error")
            self.assertIn("Security block", resp["error"])

        # 2. Sensitive directories write protection
        for bad_dir_file in (".termux/boot/run.sh", ".ssh/authorized_keys", ".termux_agent/secret"):
            await client.send(json.dumps({"action": "write_file", "path": bad_dir_file, "content": "malicious"}))
            resp = await self.receive(client)
            self.assertEqual(resp["type"], "error")
            self.assertIn("Security block", resp["error"])

        # 3. Path traversal outside HOME
        await client.send(json.dumps({"action": "write_file", "path": "../../escaped.txt", "content": "malicious"}))
        resp = await self.receive(client)
        self.assertEqual(resp["type"], "error")
        self.assertIn("Security block", resp["error"])

        # 4. Sensitive directory read protection (.ssh)
        await client.send(json.dumps({"action": "read_file", "path": ".ssh/id_rsa"}))
        resp = await self.receive(client)
        self.assertEqual(resp["type"], "error")
        self.assertIn("Security block", resp["error"])

        # 5. Normal file write and read inside HOME should succeed
        await client.send(json.dumps({"action": "write_file", "path": "docs/safe.txt", "content": "safe content"}))
        resp = await self.receive(client)
        self.assertEqual(resp["type"], "file_written")
        self.assertTrue(resp["success"])

        await client.send(json.dumps({"action": "read_file", "path": "docs/safe.txt"}))
        read_resp = await self.receive(client)
        self.assertEqual(read_resp["type"], "file_content")
        self.assertEqual(read_resp["content"], "safe content")

    async def test_ping_with_ping_id(self):
        client = await self.connect()
        await client.send(json.dumps({"action": "ping", "timestamp": 456, "ping_id": "test-ping-123"}))
        resp = await self.receive(client)
        self.assertEqual(resp["type"], "pong")
        self.assertEqual(resp.get("ping_id"), "test-ping-123")
        self.assertEqual(resp.get("timestamp"), 456)

    async def test_file_transfer_with_request_id_and_regular_file_check(self):
        client = await self.connect()
        # write with request_id
        await client.send(json.dumps({
            "action": "write_file", "path": "docs/req_test.txt", "content": "with-id", "request_id": "req-1"
        }))
        resp = await self.receive(client)
        self.assertEqual(resp["type"], "file_written")
        self.assertEqual(resp.get("request_id"), "req-1")

        # read with request_id
        await client.send(json.dumps({
            "action": "read_file", "path": "docs/req_test.txt", "request_id": "req-2"
        }))
        resp = await self.receive(client)
        self.assertEqual(resp["type"], "file_content")
        self.assertEqual(resp.get("request_id"), "req-2")
        self.assertEqual(resp.get("content"), "with-id")

        # read directory should fail with error that it's not a regular file
        await client.send(json.dumps({
            "action": "read_file", "path": "docs", "request_id": "req-3"
        }))
        resp = await self.receive(client)
        self.assertEqual(resp["type"], "error")
        self.assertEqual(resp.get("request_id"), "req-3")
        self.assertIn("not a regular file", resp["error"])

    async def test_cd_with_variable_expansion(self):
        client = await self.connect()
        with patch.dict(os.environ, {"MY_TEST_DIR": "test_var_dir"}):
            dir_path = Path(TEST_HOME.name) / "test_var_dir"
            dir_path.mkdir(exist_ok=True)
            await self.execute(client, "cd $MY_TEST_DIR")
            messages = await self.until(client, "completed")
            self.assertEqual(messages[-1]["exit_code"], 0)
            self.assertEqual(os.path.normpath(messages[-1]["cwd"]), os.path.normpath(str(dir_path)))

    async def test_unclosed_quotes_command_does_not_crash_daemon(self):
        client = await self.connect()
        # Command with unclosed quote passed to shell
        await self.execute(client, "echo \"unclosed quote")
        messages = await self.until(client, "completed")
        self.assertIn("completed", [m["type"] for m in messages])

    @unittest.skipIf(sys.platform == "win32", "Process /proc inspection is Linux-specific")
    async def test_disconnect_terminates_child(self):
        client = await self.connect()
        command = shlex.quote(sys.executable) + " -u -c " + shlex.quote(
            "import os,time; print(os.getpid(), flush=True); time.sleep(30)"
        )
        await self.execute(client, command)
        pid = int((await self.receive(client))["data"].strip())
        await client.close()
        for _ in range(100):
            try:
                os.kill(pid, 0)
                stat = Path(f"/proc/{pid}/stat")
                if stat.exists() and stat.read_text().split()[2] == "Z":
                    return
            except ProcessLookupError:
                return
            await asyncio.sleep(0.05)
        self.fail("Child process survived client disconnect")


if __name__ == "__main__":
    unittest.main()


class ServiceOwnershipTests(unittest.IsolatedAsyncioTestCase):
    async def test_duplicate_start_preserves_running_pid(self):
        with tempfile.TemporaryDirectory() as directory:
            pid_file = Path(directory) / 'daemon.pid'
            pid_file.write_text('12345')
            server = await websockets.serve(bridge.handle_connection, bridge.HOST, 0)
            port = server.sockets[0].getsockname()[1]
            try:
                with patch.object(bridge, 'PORT', port), patch.object(bridge, 'PID_FILE', pid_file):
                    with self.assertRaises(OSError):
                        await bridge.main()
                self.assertEqual(pid_file.read_text(), '12345')
            finally:
                server.close()
                await server.wait_closed()
                for sig in (bridge.signal.SIGTERM, bridge.signal.SIGINT):
                    try:
                        asyncio.get_running_loop().remove_signal_handler(sig)
                    except (NotImplementedError, AttributeError):
                        pass

    async def test_cleanup_only_removes_owned_pid(self):
        with tempfile.TemporaryDirectory() as directory:
            pid_file = Path(directory) / 'daemon.pid'
            with patch.object(bridge, 'PID_FILE', pid_file):
                pid_file.write_text('12345')
                bridge.remove_owned_pid_file()
                self.assertTrue(pid_file.exists())
                pid_file.write_text(str(os.getpid()))
                bridge.remove_owned_pid_file()
                self.assertFalse(pid_file.exists())
