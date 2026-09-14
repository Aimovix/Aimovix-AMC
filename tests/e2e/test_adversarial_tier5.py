"""
Tier 5 Adversarial Challenger Stress & Boundary Test Suite.
Adversarial white-box tests covering protocol fuzzing, malformed frames,
rapid reconnect thrashing, interruption races, oversized payloads,
and resource leak verification.
"""

import asyncio
import json
import os
import pathlib
import sys
import tempfile
import time
import unittest
from typing import Any, Dict, List, Optional

import websockets

from tests.e2e.harness import (
    BaseE2ETestCase,
    DaemonProcess,
    E2EWebSocketClient,
    find_free_port,
    make_python_cmd,
    PROJECT_ROOT,
)


def is_pid_alive(pid: int) -> bool:
    """Check if an OS process with the given PID is currently active."""
    if pid <= 0:
        return False
    if sys.platform == "win32":
        import ctypes
        PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        SYNCHRONIZE = 0x00100000
        h = ctypes.windll.kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE, False, pid)
        if not h:
            return False
        exit_code = ctypes.c_ulong()
        ctypes.windll.kernel32.GetExitCodeProcess(h, ctypes.byref(exit_code))
        ctypes.windll.kernel32.CloseHandle(h)
        return exit_code.value == 259  # STILL_ACTIVE
    else:
        try:
            os.kill(pid, 0)
            return True
        except (OSError, ProcessLookupError):
            return False


class TestAdversarialProtocolFuzzing(BaseE2ETestCase):
    """Adversarial testing against malformed frames, bad types, and payload limits."""

    async def test_fuzz_garbage_binary_and_corrupt_text_frames(self):
        """Verify daemon does not crash on arbitrary binary or malformed text frames."""
        # 1. Send raw binary bytes (non-UTF8 / null bytes)
        corrupted_payloads = [
            b"\x00\x01\x02\xff\xfe\xfd",
            b"\x80\x81\x82\x83",
            b"GARBAGE_NON_JSON_DATA",
            b"",
        ]
        for bad_bytes in corrupted_payloads:
            if self.client.ws is not None:
                await self.client.ws.send(bad_bytes)
                resp = await self.client.recv(timeout=3.0)
                self.assertEqual(resp.get("type"), "error")
                self.assertIn("Invalid JSON object", resp.get("error", ""))

        # 2. Send malformed text frames
        malformed_texts = [
            "{unterminated json string",
            "{'single_quotes': True}",
            "not a json object at all",
            "12345",
            "true",
            "false",
            "null",
            "[1, 2, 3]",
            '{"action": "ping",,}',
        ]
        for bad_text in malformed_texts:
            await self.client.send_raw_text(bad_text)
            resp = await self.client.recv(timeout=3.0)
            self.assertEqual(resp.get("type"), "error")

        # 3. Confirm daemon is still fully healthy after corrupt flood
        pong = await self.client.ping(ping_id="liveness_post_fuzz")
        self.assertEqual(pong.get("type"), "pong")
        self.assertEqual(pong.get("ping_id"), "liveness_post_fuzz")

    async def test_fuzz_invalid_types_for_all_protocol_fields(self):
        """Test corrupted data types across all protocol actions."""
        # Type corruptions for auth (on a fresh unauthenticated connection)
        bad_auth_tokens = [12345, True, False, ["list"], {"dict": 1}, None, 0.0]
        for bad_tok in bad_auth_tokens:
            unauth_client = await self.create_client(authenticate=False)
            await unauth_client.send(raw_dict={"action": "auth", "token": bad_tok})
            resp = await unauth_client.recv(timeout=3.0)
            self.assertEqual(resp.get("type"), "auth_fail")
            await unauth_client.close()

        # Type corruptions for execute command and timeout
        bad_execute_calls = [
            {"command": 12345},
            {"command": None},
            {"command": ["echo", "hi"]},
            {"command": ""},
            {"command": "   "},
            {"command": "echo ok", "timeout_ms": "infinite"},
            {"command": "echo ok", "timeout_ms": -100},
            {"command": "echo ok", "timeout_ms": 0},
            {"command": "echo ok", "timeout_ms": 3600001},
            {"command": "echo ok", "timeout_ms": [1000]},
        ]
        for idx, payload in enumerate(bad_execute_calls):
            req = {"action": "execute", "execution_id": f"bad_exec_{idx}"}
            req.update(payload)
            await self.client.send(raw_dict=req)
            resp = await self.client.recv(timeout=3.0)
            self.assertEqual(resp.get("type"), "error", f"Failed on payload: {payload}")

        # Invalid cwd paths (non-existent path string)
        bad_cwd = "C:/non_existent_adversarial_dir_12345" if sys.platform == "win32" else "/non_existent_adversarial_dir_12345"
        await self.client.send(raw_dict={"action": "execute", "command": make_python_cmd("-c", "print('cwd')"), "cwd": bad_cwd})
        resp = await self.client.recv(timeout=3.0)
        self.assertEqual(resp.get("type"), "error")

        # Type corruptions for file operations
        bad_file_calls = [
            {"action": "write_file", "path": 12345, "content": "hello"},
            {"action": "write_file", "path": "file.txt", "content": 12345},
            {"action": "write_file", "path": "file.txt", "content": None},
            {"action": "write_file", "path": "", "content": "hello"},
            {"action": "read_file", "path": 12345},
            {"action": "read_file", "path": None},
            {"action": "read_file", "path": ""},
            {"action": "read_file_base64", "path": 12345},
            {"action": "read_file_base64", "path": ""},
        ]
        for bad_file in bad_file_calls:
            await self.client.send(raw_dict=bad_file)
            resp = await self.client.recv(timeout=3.0)
            self.assertEqual(resp.get("type"), "error")

        # Unknown action
        await self.client.send(raw_dict={"action": "malicious_undefined_action_123"})
        resp = await self.client.recv(timeout=3.0)
        self.assertEqual(resp.get("type"), "error")
        self.assertIn("Unknown action", resp.get("error", ""))

        # Verify bridge remains operational
        stdout, _, code = await self.client.execute(make_python_cmd("-c", "print('alive')"))
        self.assertEqual(code, 0)
        self.assertEqual(stdout.strip(), "alive")

    async def test_fuzz_massive_payload_injection(self):
        """Test 2MB command strings, 4MB file writes, and 6MB file transfer limits."""
        # 1. 2MB command string injection (exceeds OS shell limits, daemon must reject or handle cleanly)
        large_cmd = "echo " + ("A" * (2 * 1024 * 1024))
        await self.client.send(raw_dict={
            "action": "execute",
            "command": large_cmd,
            "execution_id": "large_cmd_exec",
            "timeout_ms": 10000
        })

        # Daemon catches OS exception and returns error or completed, never crashes
        resp = await self.client.recv(timeout=5.0)
        self.assertIn(resp.get("type"), ("error", "completed", "stdout"))
        # Consume any trailing frames for this execution
        if resp.get("type") == "stdout":
            await self.client.collect_until("completed", timeout=5.0)

        # 2. 4MB file write
        large_content = "X" * (4 * 1024 * 1024)
        write_resp = await self.client.write_file("adversarial_large_file.txt", large_content, request_id="write_4mb")
        self.assertEqual(write_resp.get("type"), "file_written")
        self.assertTrue(write_resp.get("success"))

        # Verify written file size on disk
        written_path = pathlib.Path(self.daemon.home_dir) / "adversarial_large_file.txt"
        self.assertTrue(written_path.exists())
        self.assertEqual(written_path.stat().st_size, 4 * 1024 * 1024)

        # 3. Exceed 5MB file transfer limit (write 5.5MB directly to disk, attempt read)
        oversized_path = pathlib.Path(self.daemon.home_dir) / "oversized_file.dat"
        oversized_path.write_bytes(b"Z" * (int(5.5 * 1024 * 1024)))

        read_resp = await self.client.read_file("oversized_file.dat", request_id="read_5.5mb")
        self.assertEqual(read_resp.get("type"), "error")
        self.assertIn("5 MiB transfer limit", read_resp.get("error", ""))

        # 4. Clean up disk artifacts
        if written_path.exists():
            written_path.unlink()
        if oversized_path.exists():
            oversized_path.unlink()

        # Bridge remains responsive
        pong = await self.client.ping(ping_id="after_massive_payloads")
        self.assertEqual(pong.get("type"), "pong")

    async def test_oversized_frame_exceeding_max_size_does_not_kill_daemon(self):
        """Frames exceeding WebSocket max_size (10MB) close connection without killing daemon."""
        unauth = await self.create_client(authenticate=False)
        # 11MB frame exceeds 10MB limit
        massive_frame = "M" * (11 * 1024 * 1024)
        with self.assertRaises((websockets.exceptions.ConnectionClosed, websockets.exceptions.PayloadTooBig)):
            if unauth.ws is not None:
                await unauth.ws.send(massive_frame)
                await unauth.recv(timeout=3.0)
        await unauth.close()

        # Confirm daemon process is still running and serving other clients
        self.assertIsNone(self.daemon.process.poll())
        pong = await self.client.ping(ping_id="daemon_alive_after_11mb")
        self.assertEqual(pong.get("type"), "pong")


class TestAdversarialRaceConditions(BaseE2ETestCase):
    """Adversarial testing of concurrency, timing races, and connection churn."""

    async def test_rapid_connect_disconnect_churn(self):
        """Thrash the daemon with 40 rapid connect/disconnect cycles."""
        for i in range(40):
            c = E2EWebSocketClient(self.daemon.url)
            await c.connect()
            if i % 2 == 0:
                await c.authenticate(self.daemon.token)
            if i % 4 == 0:
                await c.ping(ping_id=f"churn_{i}")
            # Abruptly close
            await c.close()

        # Verify main client is still healthy and responsive
        stdout, _, code = await self.client.execute(make_python_cmd("-c", "print('churn_complete')"))
        self.assertEqual(code, 0)
        self.assertEqual(stdout.strip(), "churn_complete")

    async def test_rapid_interruption_races(self):
        """Dispatch a command and immediately send interrupt with zero delay (15 cycles)."""
        for i in range(15):
            exec_id = f"race_exec_{i}"
            # Send execute
            await self.client.send(raw_dict={
                "action": "execute",
                "command": make_python_cmd("-c", "import time; time.sleep(5)"),
                "execution_id": exec_id,
                "timeout_ms": 10000
            })
            # Immediately send interrupt
            await self.client.send(raw_dict={
                "action": "interrupt",
                "execution_id": exec_id
            })

            # Collect until completed
            frames = await self.client.collect_until("completed", timeout=5.0)
            completed_frame = [f for f in frames if f.get("type") == "completed"]
            self.assertTrue(len(completed_frame) > 0)
            self.assertEqual(completed_frame[0].get("execution_id"), exec_id)

        # Confirm subsequent command executes normally
        stdout, _, code = await self.client.execute(make_python_cmd("-c", "print('after_races')"))
        self.assertEqual(code, 0)
        self.assertEqual(stdout.strip(), "after_races")

    async def test_interrupt_stale_and_nonexistent_executions(self):
        """Verify interrupt behavior when execution is already finished or nonexistent."""
        # 1. Interrupt when idle (no command has run)
        await self.client.send(raw_dict={"action": "interrupt", "execution_id": "nonexistent_123"})
        resp = await self.client.recv(timeout=3.0)
        self.assertEqual(resp.get("type"), "error")
        self.assertIn("no longer active", resp.get("error", ""))

        # 2. Run command to completion, then send interrupt for it
        stdout, _, code = await self.client.execute(make_python_cmd("-c", "print('fast_done')"), execution_id="fast_cmd")
        self.assertEqual(code, 0)

        await self.client.send(raw_dict={"action": "interrupt", "execution_id": "fast_cmd"})
        resp = await self.client.recv(timeout=3.0)
        self.assertEqual(resp.get("type"), "error")
        self.assertIn("no longer active", resp.get("error", ""))

    async def test_mid_stream_disconnect_heavy_burst(self):
        """Disconnect client abruptly while long subprocess produces massive stdout stream."""
        churn_client = await self.create_client(authenticate=True)
        # Launch process spewing 5,000 lines
        churn_cmd = make_python_cmd("-c", "import time; [print('STREAM_BURST_' + str(i)) for i in range(5000)]")
        await churn_client.send(raw_dict={
            "action": "execute",
            "command": churn_cmd,
            "execution_id": "stream_burst_exec",
            "timeout_ms": 15000
        })

        # Wait for first few chunks
        first_frame = await churn_client.recv(timeout=3.0)
        self.assertEqual(first_frame.get("type"), "stdout")

        # Abruptly kill client connection mid-stream
        await churn_client.close()

        # Allow daemon time to handle connection loss and register detached session
        await asyncio.sleep(0.5)

        # Confirm daemon process did not crash with BrokenPipeError or unhandled exception
        self.assertIsNone(self.daemon.process.poll())

        # Main client should continue to operate without interference
        stdout, _, code = await self.client.execute(make_python_cmd("-c", "print('main_healthy')"))
        self.assertEqual(code, 0)
        self.assertEqual(stdout.strip(), "main_healthy")

    async def test_concurrent_multi_client_isolation(self):
        """8 concurrent clients performing parallel executions without cross-talk."""
        clients = [await self.create_client(authenticate=True) for _ in range(8)]

        async def run_client_task(c: E2EWebSocketClient, idx: int):
            cmd = make_python_cmd("-c", f"import time; time.sleep(0.1); print('CLIENT_RESULT_{idx}')")
            out, err, code = await c.execute(cmd, execution_id=f"client_{idx}_exec")
            self.assertEqual(code, 0)
            self.assertEqual(out.strip(), f"CLIENT_RESULT_{idx}")
            pong = await c.ping(ping_id=f"ping_{idx}")
            self.assertEqual(pong.get("ping_id"), f"ping_{idx}")

        await asyncio.gather(*(run_client_task(c, i) for i, c in enumerate(clients)))


class TestAdversarialResourceLeaks(BaseE2ETestCase):
    """Adversarial verification of process termination, PID files, and pipe cleanup."""

    async def test_timeout_stops_execution_and_frees_session(self):
        """Verify command timeout terminates the command and frees the bridge session."""
        cmd = make_python_cmd("-c", "import time; time.sleep(30)")

        # Dispatch with 1-second timeout
        await self.client.send(raw_dict={
            "action": "execute",
            "command": cmd,
            "execution_id": "timeout_exec_test",
            "timeout_ms": 1000
        })

        # Wait for timeout error frame
        frames = await self.client.collect_until("error", timeout=5.0)
        err_frame = [f for f in frames if f.get("type") == "error"][0]
        self.assertIn("timed out and was stopped", err_frame.get("error", ""))

        # Verify session is immediately freed and capable of executing new commands
        stdout, _, code = await self.client.execute(
            make_python_cmd("-c", "print('session_ready_after_timeout')"),
            execution_id="post_timeout_exec"
        )
        self.assertEqual(code, 0)
        self.assertEqual(stdout.strip(), "session_ready_after_timeout")

    @unittest.skipIf(sys.platform == "win32", "Process group termination (os.killpg) is POSIX/Termux-specific")
    async def test_timeout_kills_process_group_completely_on_posix(self):
        """Verify command timeout kills the entire process group in POSIX environments."""
        pid_file = pathlib.Path(self.daemon.home_dir) / "timeout_posix.pid"
        if pid_file.exists():
            pid_file.unlink()

        cmd = make_python_cmd(
            "-c",
            f"import os, time; open(r'{pid_file}', 'w').write(str(os.getpid())); time.sleep(30)"
        )

        await self.client.send(raw_dict={
            "action": "execute",
            "command": cmd,
            "execution_id": "timeout_posix_exec",
            "timeout_ms": 1000
        })

        await self.client.collect_until("error", timeout=5.0)
        self.assertTrue(pid_file.exists())
        child_pid = int(pid_file.read_text().strip())

        await asyncio.sleep(0.5)
        self.assertFalse(is_pid_alive(child_pid))

    async def test_interrupt_stops_execution_and_frees_session(self):
        """Verify emergency stop / interrupt terminates execution and frees session."""
        cmd = make_python_cmd("-c", "import time; time.sleep(30)")

        await self.client.send(raw_dict={
            "action": "execute",
            "command": cmd,
            "execution_id": "interrupt_exec_test",
            "timeout_ms": 30000
        })

        # Allow process to start
        await asyncio.sleep(0.2)

        # Send interrupt
        await self.client.send(raw_dict={
            "action": "interrupt",
            "execution_id": "interrupt_exec_test"
        })

        frames = await self.client.collect_until("completed", timeout=5.0)
        comp_frame = [f for f in frames if f.get("type") == "completed"][0]
        self.assertEqual(comp_frame.get("exit_code"), 130)

        # Verify session is immediately ready for next command
        stdout, _, code = await self.client.execute(
            make_python_cmd("-c", "print('session_ready_after_interrupt')"),
            execution_id="post_interrupt_exec"
        )
        self.assertEqual(code, 0)
        self.assertEqual(stdout.strip(), "session_ready_after_interrupt")

    @unittest.skipIf(sys.platform == "win32", "Process group termination (os.killpg) is POSIX/Termux-specific")
    async def test_interrupt_kills_process_group_completely_on_posix(self):
        """Verify interrupt kills the entire process group in POSIX environments."""
        pid_file = pathlib.Path(self.daemon.home_dir) / "interrupt_posix.pid"
        if pid_file.exists():
            pid_file.unlink()

        cmd = make_python_cmd(
            "-c",
            f"import os, time; open(r'{pid_file}', 'w').write(str(os.getpid())); time.sleep(30)"
        )

        await self.client.send(raw_dict={
            "action": "execute",
            "command": cmd,
            "execution_id": "interrupt_posix_exec",
            "timeout_ms": 30000
        })

        deadline = time.time() + 5.0
        while time.time() < deadline:
            if pid_file.exists() and pid_file.read_text().strip():
                break
            await asyncio.sleep(0.05)

        self.assertTrue(pid_file.exists())
        child_pid = int(pid_file.read_text().strip())
        self.assertTrue(is_pid_alive(child_pid))

        await self.client.send(raw_dict={"action": "interrupt", "execution_id": "interrupt_posix_exec"})
        await self.client.collect_until("completed", timeout=5.0)
        await asyncio.sleep(0.5)
        self.assertFalse(is_pid_alive(child_pid))

    async def test_pipe_and_descriptor_hygiene_under_rapid_commands(self):
        """Run 50 rapid sequential commands without pipe warnings or resource exhaustion."""
        for i in range(50):
            stdout, stderr, code = await self.client.execute(
                make_python_cmd("-c", f"import sys; sys.stdout.write('OUT_{i}\\n'); sys.stderr.write('ERR_{i}\\n')"),
                execution_id=f"seq_{i}"
            )
            self.assertEqual(code, 0)
            self.assertEqual(stdout.strip(), f"OUT_{i}")
            self.assertEqual(stderr.strip(), f"ERR_{i}")

        # Final check on daemon
        self.assertIsNone(self.daemon.process.poll())

    async def test_duplicate_daemon_binding_preserves_running_pid(self):
        """Second daemon instance attempting to bind to same port fails without corrupting PID file."""
        pid_path = pathlib.Path(self.daemon.home_dir) / ".termux_agent" / "daemon.pid"
        self.assertTrue(pid_path.exists())
        original_pid = pid_path.read_text().strip()
        self.assertEqual(original_pid, str(self.daemon.pid))

        # Attempt to launch duplicate daemon with same HOME and port
        env = os.environ.copy()
        env["HOME"] = self.daemon.home_dir
        env["USERPROFILE"] = self.daemon.home_dir
        env["BRIDGE_PORT"] = str(self.daemon.port)

        dup_proc = await asyncio.create_subprocess_exec(
            sys.executable,
            str(PROJECT_ROOT / "termux-bridge" / "bridge_daemon.py"),
            env=env,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        _, _ = await dup_proc.communicate()
        self.assertNotEqual(dup_proc.returncode, 0)

        # Verify original PID file was not modified or deleted
        self.assertTrue(pid_path.exists())
        self.assertEqual(pid_path.read_text().strip(), original_pid)
        self.assertIsNone(self.daemon.process.poll())


if __name__ == "__main__":
    unittest.main()
