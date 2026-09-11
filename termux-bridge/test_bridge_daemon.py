"""Integration tests for token authentication, process control, and connection isolation."""
import asyncio
import importlib.util
import json
import os
from pathlib import Path
import shlex
import sys
import tempfile
import unittest
from unittest.mock import patch

import websockets

TEST_HOME = tempfile.TemporaryDirectory()
with patch.dict(os.environ, {"HOME": TEST_HOME.name}):
    spec = importlib.util.spec_from_file_location("bridge", Path(__file__).with_name("bridge_daemon.py"))
    bridge = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bridge)


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
        self.assertEqual(bridge.TOKEN_FILE.stat().st_mode & 0o777, 0o600)

    async def test_ping_and_interrupt_work_during_execution(self):
        client = await self.connect()
        await self.execute(client, "echo ready; sleep 30")
        self.assertIn("ready", (await self.receive(client))["data"])
        await client.send(json.dumps({"action": "ping", "timestamp": 123}))
        self.assertEqual((await self.receive(client))["type"], "pong")
        await client.send(json.dumps({"action": "interrupt"}))
        messages = await self.until(client, "completed")
        self.assertTrue(any(m["type"] == "interrupted" for m in messages))
        self.assertEqual(messages[-1]["exit_code"], 130)
        await self.execute(client, "echo usable", execution_id="next")
        messages = await self.until(client, "completed")
        self.assertIn("usable", "".join(m.get("data", "") for m in messages))
        self.assertEqual(messages[-1]["exit_code"], 0)

    async def test_busy_execution_is_rejected_without_replacing_process(self):
        client = await self.connect()
        await self.execute(client, "echo ready; sleep 30")
        await self.receive(client)
        await self.execute(client, "echo should-not-run", execution_id="second")
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
        await self.execute(first, "echo first; sleep 30")
        await self.receive(first)
        await second.send(json.dumps({"action": "interrupt"}))
        self.assertEqual((await self.receive(second))["type"], "interrupted")
        await self.execute(second, "pwd")
        result = await self.until(second, "completed")
        self.assertEqual("".join(m.get("data", "") for m in result).strip(), TEST_HOME.name)
        await first.send(json.dumps({"action": "interrupt"}))
        result = await self.until(first, "completed")
        self.assertEqual(result[-1]["exit_code"], 130)

    async def test_timeout_stops_process(self):
        client = await self.connect()
        await self.execute(client, "sleep 30", timeout_ms=50)
        result = await self.until(client, "error")
        self.assertIn("timed out", result[-1]["error"])
        await self.execute(client, "echo after-timeout")
        self.assertEqual((await self.until(client, "completed"))[-1]["exit_code"], 0)

    async def test_long_output_without_newline_streams(self):
        client = await self.connect()
        command = shlex.quote(sys.executable) + " -c " + shlex.quote("import sys; sys.stdout.write('x' * 100000)")
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
