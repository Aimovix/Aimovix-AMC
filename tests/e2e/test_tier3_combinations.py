"""
Aimovix-AMC E2E Test Suite - Tier 3: Cross-Feature Combinations.
Implements pairwise and multi-feature interaction tests covering authentication,
streaming execution, emergency stop interrupts, heartbeat pings, file operations,
and connection lifecycle recovery.
"""

import asyncio
import base64
import hashlib
import json
import os
import pathlib
import sys
import unittest
from typing import List

from tests.e2e.harness import (
    DaemonProcess,
    E2EWebSocketClient,
    HeadTailBuffer,
    make_python_cmd,
)


class TestTier3Combinations(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        cls.daemon = DaemonProcess().start()

    @classmethod
    def tearDownClass(cls):
        cls.daemon.stop()

    async def asyncSetUp(self):
        self.clients: List[E2EWebSocketClient] = []

    async def asyncTearDown(self):
        for c in self.clients:
            await c.close()

    async def create_client(self, auth: bool = True) -> E2EWebSocketClient:
        c = E2EWebSocketClient(self.daemon.url)
        await c.connect()
        self.clients.append(c)
        if auth:
            resp = await c.authenticate(self.daemon.token)
            self.assertEqual(resp.get("type"), "auth_ok")
        return c

    async def test_combo_01_auth_token_and_immediate_command_execution(self):
        # Authenticate and immediately execute a command in sequence
        client = await self.create_client(auth=True)
        stdout, stderr, code = await client.execute(
            make_python_cmd("-c", "print('pipeline_ok', flush=True)"),
            execution_id="combo_1"
        )
        self.assertEqual(code, 0)
        self.assertIn("pipeline_ok", stdout)

    async def test_combo_02_auth_failure_blocks_command_and_terminates(self):
        # Invalid token yields auth_fail and closes socket
        client = await self.create_client(auth=False)
        resp = await client.authenticate("completely_wrong_token")
        self.assertEqual(resp.get("type"), "auth_fail")
        # Attempting execute afterwards should fail as socket is closed
        with self.assertRaises(Exception):
            await client.send("execute", command="echo fail")
            await client.recv()

    async def test_combo_03_active_command_with_emergency_stop_interrupt(self):
        # Launch long-running task, wait for first chunk, then send interrupt
        client = await self.create_client(auth=True)
        cmd = make_python_cmd("-c", "import time; print('running', flush=True); time.sleep(30)")
        await client.send("execute", command=cmd, execution_id="combo_int")
        while True:
            msg = await client.recv()
            if msg.get("type") == "stdout" and "running" in msg.get("data", ""):
                break
        frames = await client.interrupt(execution_id="combo_int")
        types = [f.get("type") for f in frames]
        self.assertIn("interrupted", types)
        completed = [f for f in frames if f.get("type") == "completed"][-1]
        self.assertEqual(completed.get("exit_code"), 130)

    async def test_combo_04_streaming_stdout_with_concurrent_heartbeat_pings(self):
        # Command streams chunks slowly while client sends pings concurrently
        client = await self.create_client(auth=True)
        cmd = make_python_cmd(
            "-u", "-c",
            "import time; [print(f'chunk_{i}', flush=True) or time.sleep(0.05) for i in range(10)]"
        )
        await client.send("execute", command=cmd, execution_id="stream_ping")

        received_chunks = []
        received_pongs = []

        # Interleave ping requests while reading stream
        for i in range(5):
            await client.send("ping", ping_id=f"combo_p_{i}", timestamp=i * 100)
            # Read until pong or chunk
            msg = await client.recv()
            if msg.get("type") == "pong":
                received_pongs.append(msg.get("ping_id"))
            elif msg.get("type") == "stdout":
                received_chunks.append(msg.get("data", ""))

        # Collect the remaining frames until completion
        frames = await client.collect_until("completed")
        for f in frames:
            if f.get("type") == "pong":
                received_pongs.append(f.get("ping_id"))
            elif f.get("type") == "stdout":
                received_chunks.append(f.get("data", ""))

        full_stdout = "".join(received_chunks)
        for i in range(10):
            self.assertIn(f"chunk_{i}", full_stdout)
        self.assertEqual(len(received_pongs), 5)

    async def test_combo_05_file_write_and_shell_execution_roundtrip(self):
        # Write a Python script to disk, then execute it and inspect output
        client = await self.create_client(auth=True)
        script_code = "import math; print('SQRT_16 =', math.isqrt(16))\n"
        w_resp = await client.write_file("calc.py", script_code)
        self.assertEqual(w_resp.get("type"), "file_written")

        stdout, stderr, code = await client.execute(
            make_python_cmd("calc.py"),
            execution_id="run_calc"
        )
        self.assertEqual(code, 0)
        self.assertIn("SQRT_16 = 4", stdout)

    async def test_combo_06_command_creates_file_and_file_read(self):
        # Command generates an artifact file, client reads it back via read_file
        client = await self.create_client(auth=True)
        cmd = make_python_cmd(
            "-c",
            "open('generated_data.json', 'w').write('{\"status\": \"generated\", \"val\": 42}')"
        )
        stdout, _, code = await client.execute(cmd)
        self.assertEqual(code, 0)

        read_resp = await client.read_file("generated_data.json")
        self.assertEqual(read_resp.get("type"), "file_content")
        parsed = json.loads(read_resp.get("content", "{}"))
        self.assertEqual(parsed.get("status"), "generated")
        self.assertEqual(parsed.get("val"), 42)

    async def test_combo_07_binary_file_write_and_read_base64(self):
        # Write binary content encoded in base64, verify hash via python, read via read_file_base64
        client = await self.create_client(auth=True)
        raw_bytes = bytes(range(256)) * 4  # 1024 bytes
        b64_content = base64.b64encode(raw_bytes).decode("ascii")

        # Write binary file via python helper
        cmd = make_python_cmd(
            "-c",
            f"import base64; open('binary_data.bin', 'wb').write(base64.b64decode('{b64_content}'))"
        )
        _, _, code = await client.execute(cmd)
        self.assertEqual(code, 0)

        # Read back with read_file_base64
        resp = await client.read_file_base64("binary_data.bin")
        self.assertEqual(resp.get("type"), "file_base64")
        received_bytes = base64.b64decode(resp.get("data", ""))
        self.assertEqual(hashlib.sha256(received_bytes).hexdigest(), hashlib.sha256(raw_bytes).hexdigest())

    async def test_combo_08_concurrent_clients_with_different_cwds(self):
        # Client 1 switches directory, Client 2 stays in root; verify isolation
        c1 = await self.create_client(auth=True)
        c2 = await self.create_client(auth=True)

        subdir = pathlib.Path(self.daemon.home_dir) / "isolated_subdir"
        subdir.mkdir(exist_ok=True)

        # Client 1 runs cd
        await c1.send("execute", command=f"cd isolated_subdir", execution_id="cd_1")
        frames1 = await c1.collect_until("completed")
        self.assertEqual(frames1[-1].get("exit_code"), 0)

        # Client 1 checks cwd
        out1, _, _ = await c1.execute(make_python_cmd("-c", "import os; print(os.getcwd())"), execution_id="pwd_1")
        # Client 2 checks cwd
        out2, _, _ = await c2.execute(make_python_cmd("-c", "import os; print(os.getcwd())"), execution_id="pwd_2")

        self.assertIn("isolated_subdir", out1)
        self.assertNotIn("isolated_subdir", out2)

    async def test_combo_09_rapid_command_chaining(self):
        # Dispatch 10 sequential commands rapidly, each waiting for previous completion
        client = await self.create_client(auth=True)
        for i in range(10):
            stdout, _, code = await client.execute(
                make_python_cmd("-c", f"print('chain_{i}')"),
                execution_id=f"chain_{i}"
            )
            self.assertEqual(code, 0)
            self.assertIn(f"chain_{i}", stdout)

    async def test_combo_10_command_timeout_then_clean_subsequent_execution(self):
        # Command times out after 100ms, then client executes a normal command cleanly
        client = await self.create_client(auth=True)
        cmd_timeout = make_python_cmd("-c", "import time; time.sleep(5)")
        await client.send("execute", command=cmd_timeout, execution_id="to_test", timeout_ms=100)
        frames = await client.collect_until("error")
        self.assertIn("timed out", frames[-1].get("error", "").lower())

        # Immediately run valid command
        out, _, code = await client.execute(make_python_cmd("-c", "print('after_timeout')"), execution_id="post_to")
        self.assertEqual(code, 0)
        self.assertIn("after_timeout", out)

    async def test_combo_11_path_traversal_attempt_then_safe_execution(self):
        # Path traversal fails with security block, then normal file operations work
        client = await self.create_client(auth=True)
        resp = await client.write_file("../../forbidden.txt", "attack")
        self.assertEqual(resp.get("type"), "error")
        self.assertIn("security block", resp.get("error", "").lower())

        # Legitimate file write succeeds
        safe_resp = await client.write_file("safe_file.txt", "safe_data")
        self.assertEqual(safe_resp.get("type"), "file_written")

    async def test_combo_12_sys_info_query_during_active_idle_connection(self):
        # Query sys_info before and after command execution
        client = await self.create_client(auth=True)
        info1 = await client.sys_info()
        self.assertEqual(info1.get("type"), "sys_info")

        stdout, _, code = await client.execute(make_python_cmd("-c", "print('between_sys_info')"))
        self.assertEqual(code, 0)

        info2 = await client.sys_info()
        self.assertEqual(info2.get("type"), "sys_info")
        d1 = info1.get("data") or info1.get("system")
        d2 = info2.get("data") or info2.get("system")
        self.assertEqual(d1.get("os"), d2.get("os"))

    async def test_combo_13_interrupted_command_followed_by_immediate_new_command(self):
        # Immediately after interrupting a command, dispatch a new one
        client = await self.create_client(auth=True)
        cmd = make_python_cmd("-c", "import time; print('start_int', flush=True); time.sleep(30)")
        await client.send("execute", command=cmd, execution_id="to_be_interrupted")
        while True:
            msg = await client.recv()
            if msg.get("type") == "stdout" and "start_int" in msg.get("data", ""):
                break
        await client.interrupt(execution_id="to_be_interrupted")

        # Next command dispatches and succeeds
        out, _, code = await client.execute(make_python_cmd("-c", "print('immediate_followup')"), execution_id="followup")
        self.assertEqual(code, 0)
        self.assertIn("immediate_followup", out)

    async def test_combo_14_stderr_burst_with_concurrent_ping(self):
        # Intense stderr stream with ping
        client = await self.create_client(auth=True)
        cmd = make_python_cmd(
            "-u", "-c",
            "import sys, time; [sys.stderr.write(f'err_{i}\\n') or sys.stderr.flush() or time.sleep(0.02) for i in range(5)]"
        )
        await client.send("execute", command=cmd, execution_id="err_stream")
        await client.send("ping", ping_id="err_ping", timestamp=777)

        pongs = []
        stderrs = []
        frames = await client.collect_until("completed")
        for f in frames:
            if f.get("type") == "pong":
                pongs.append(f)
            elif f.get("type") == "stderr":
                stderrs.append(f.get("data", ""))

        self.assertEqual(len(pongs), 1)
        self.assertEqual(pongs[0].get("ping_id"), "err_ping")
        self.assertIn("err_0", "".join(stderrs))

    async def test_combo_15_head_tail_buffer_streaming_integration(self):
        # Stream 150KB output and buffer it with HeadTailBuffer
        client = await self.create_client(auth=True)
        buf = HeadTailBuffer(max_head=10000, max_tail=10000)

        cmd = make_python_cmd("-u", "-c", "import sys; sys.stdout.write('H' * 10000 + 'M' * 50000 + 'T' * 10000)")
        await client.send("execute", command=cmd, execution_id="htb_stream")

        frames = await client.collect_until("completed")
        for f in frames:
            if f.get("type") == "stdout":
                buf.append(f.get("data", ""))

        result = buf.build()
        self.assertTrue(result.startswith("H" * 10000))
        self.assertTrue(result.endswith("T" * 10000))
        self.assertIn("characters omitted", result)


if __name__ == "__main__":
    unittest.main()
