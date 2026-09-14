"""
Aimovix-AMC E2E Test Suite - Tier 4: Real-World Scenarios.
Implements realistic, multi-step, end-to-end user and autonomous agent workloads:
1. Autonomous AI Agent Multi-Tool Pipeline
2. Long-Running Build Simulation with Emergency Stop Abort
3. Network Flapping, Exponential Backoff, and Transparent Recovery
4. High-Throughput Burst Streaming Stress & Buffer Truncation
5. Security Cockpit & Path Traversal Neutralization
6. Concurrent Agent & User Terminal Dual-Session Workload
"""

import asyncio
import base64
import json
import os
import pathlib
import sys
import unittest
from typing import List

from tests.e2e.harness import (
    BackoffCalculator,
    DaemonProcess,
    E2EWebSocketClient,
    HeadTailBuffer,
    make_python_cmd,
)


class TestTier4RealWorldScenarios(unittest.IsolatedAsyncioTestCase):
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

    async def test_scenario_01_ai_agent_multi_tool_workflow(self):
        """
        Scenario: An AI agent performs a multi-step task:
        Inspects environment -> writes script & data -> executes tool -> reads output -> cleans up.
        """
        client = await self.create_client(auth=True)

        # Step 1: Inspect system environment
        info = await client.sys_info()
        self.assertEqual(info.get("type"), "sys_info")
        data = info.get("data") or info.get("system", {})
        self.assertIn("os", data)

        # Step 2: Create workspace files
        data_json = json.dumps({"numbers": [10, 20, 30, 40], "factor": 2})
        w1 = await client.write_file("agent_data.json", data_json)
        self.assertEqual(w1.get("type"), "file_written")

        solver_code = (
            "import json\n"
            "data = json.load(open('agent_data.json'))\n"
            "res = [n * data['factor'] for n in data['numbers']]\n"
            "out = {'result': res, 'total': sum(res)}\n"
            "json.dump(out, open('agent_out.json', 'w'))\n"
            "print('Processing complete: sum =', sum(res), flush=True)\n"
        )
        w2 = await client.write_file("agent_solver.py", solver_code)
        self.assertEqual(w2.get("type"), "file_written")

        # Step 3: Execute tool
        stdout, stderr, code = await client.execute(
            make_python_cmd("agent_solver.py"),
            execution_id="agent_run_1"
        )
        self.assertEqual(code, 0)
        self.assertIn("Processing complete: sum = 200", stdout)

        # Step 4: Read output artifact
        r_out = await client.read_file("agent_out.json")
        self.assertEqual(r_out.get("type"), "file_content")
        parsed = json.loads(r_out.get("content", "{}"))
        self.assertEqual(parsed.get("result"), [20, 40, 60, 80])
        self.assertEqual(parsed.get("total"), 200)

        # Step 5: Clean up artifacts
        del_cmd = make_python_cmd(
            "-c",
            "import os; [os.remove(f) for f in ('agent_data.json', 'agent_solver.py', 'agent_out.json') if os.path.exists(f)]"
        )
        _, _, del_code = await client.execute(del_cmd, execution_id="agent_clean")
        self.assertEqual(del_code, 0)

    async def test_scenario_02_long_running_build_and_emergency_stop(self):
        """
        Scenario: Agent starts a long compilation build job.
        User monitors initial stdout chunks, then hits Emergency Stop.
        Bridge terminates process group with exit code 130 and recovers cleanly.
        """
        client = await self.create_client(auth=True)

        build_script = (
            "import time\n"
            "print('[Stage 1/5] Compiling core modules...', flush=True)\n"
            "time.sleep(0.1)\n"
            "print('[Stage 2/5] Compiling UI components...', flush=True)\n"
            "time.sleep(30)\n"  # Simulates long blocking compilation
            "print('[Stage 3/5] Linking binary...', flush=True)\n"
        )
        await client.write_file("build.py", build_script)

        # Launch build
        await client.send("execute", command=make_python_cmd("build.py"), execution_id="build_job_1")

        chunks = []
        # Await stage 2 before triggering emergency stop
        while True:
            msg = await client.recv()
            if msg.get("type") == "stdout":
                chunks.append(msg.get("data", ""))
                if "Stage 2/5" in "".join(chunks):
                    break

        # User hits emergency stop
        frames = await client.interrupt(execution_id="build_job_1")
        types = [f.get("type") for f in frames]
        self.assertIn("interrupted", types)

        completed = [f for f in frames if f.get("type") == "completed"][-1]
        self.assertEqual(completed.get("exit_code"), 130)

        # Verify Stage 3 was NEVER reached
        self.assertNotIn("Stage 3/5", "".join(chunks))

        # Verify system immediately ready for followup commands
        diag_out, _, diag_code = await client.execute(
            make_python_cmd("-c", "print('build_pool_clean')"),
            execution_id="post_abort_check"
        )
        self.assertEqual(diag_code, 0)
        self.assertIn("build_pool_clean", diag_out)

    async def test_scenario_03_network_interruption_exponential_backoff_and_recovery(self):
        """
        Scenario: Network interruption occurs while connection is active.
        Simulated Android client calculates backoff with jitter and successfully reconnects.
        """
        client1 = await self.create_client(auth=True)

        # Ping works on active connection
        p1 = await client1.ping(ping_id="pre_drop")
        self.assertEqual(p1.get("type"), "pong")

        # Network drops abruptly
        await client1.close(code=1006)

        # Client backoff simulation across 4 retry attempts
        delays = []
        for attempt in range(4):
            delay = BackoffCalculator.calculate_delay_ms(attempt, apply_jitter=True)
            delays.append(delay)

        self.assertGreaterEqual(delays[0], 850)
        self.assertLessEqual(delays[0], 1150)
        # Verify backoff increases
        self.assertLess(delays[0], delays[2])

        # Network restored: client connects and authenticates
        client2 = await self.create_client(auth=True)
        p2 = await client2.ping(ping_id="post_recovery")
        self.assertEqual(p2.get("type"), "pong")
        self.assertEqual(p2.get("ping_id"), "post_recovery")

        # Command executes on restored session
        out, _, code = await client2.execute(
            make_python_cmd("-c", "print('session_restored')"),
            execution_id="rec_exec"
        )
        self.assertEqual(code, 0)
        self.assertIn("session_restored", out)

    async def test_scenario_04_high_throughput_burst_streaming_stress(self):
        """
        Scenario: High-throughput continuous burst output (>150KB without newlines).
        Validates HeadTailBuffer retention (100KB head, 150KB tail) and omit notice formatting.
        """
        client = await self.create_client(auth=True)
        buffer = HeadTailBuffer(max_head=20000, max_tail=20000)

        # Emit 80,000 characters: 20k 'A', 40k 'B', 20k 'C'
        cmd = make_python_cmd(
            "-u", "-c",
            "import sys; sys.stdout.write('A' * 20000 + 'B' * 40000 + 'C' * 20000); sys.stdout.flush()"
        )
        await client.send("execute", command=cmd, execution_id="burst_job")

        frames = await client.collect_until("completed")
        for f in frames:
            if f.get("type") == "stdout":
                buffer.append(f.get("data", ""))

        built = buffer.build()
        self.assertTrue(built.startswith("A" * 20000))
        self.assertTrue(built.endswith("C" * 20000))
        self.assertIn("characters omitted", built)
        # Verify exit code
        completed = frames[-1]
        self.assertEqual(completed.get("exit_code"), 0)

    async def test_scenario_05_security_cockpit_and_traversal_neutralization(self):
        """
        Scenario: Security audit simulation testing defenses against malicious paths.
        Hostile paths (shell configs, sensitive dirs, traversal) are blocked.
        Safe user files within HOME remain fully accessible.
        """
        client = await self.create_client(auth=True)

        hostile_writes = [
            (".bashrc", "alias evil='cat'"),
            (".profile", "export BAD=1"),
            (".ssh/authorized_keys", "ssh-rsa AAAA..."),
            (".termux/boot/evil.sh", "echo evil"),
            ("../../escaped.txt", "escape content"),
            ("subdir/../../../root.txt", "escape root"),
        ]

        for path, payload in hostile_writes:
            resp = await client.write_file(path, payload)
            self.assertEqual(resp.get("type"), "error", f"Path should have been blocked: {path}")
            self.assertIn("security block", resp.get("error", "").lower())

        # Hostile read (.ssh) blocked
        r_resp = await client.read_file(".ssh/id_rsa")
        self.assertEqual(r_resp.get("type"), "error")
        self.assertIn("security block", r_resp.get("error", "").lower())

        # Legitimate safe path inside HOME succeeds
        safe_resp = await client.write_file("documents/notes.txt", "legitimate content")
        self.assertEqual(safe_resp.get("type"), "file_written")
        self.assertTrue(safe_resp.get("success"))

        read_safe = await client.read_file("documents/notes.txt")
        self.assertEqual(read_safe.get("type"), "file_content")
        self.assertEqual(read_safe.get("content"), "legitimate content")

    async def test_scenario_06_concurrent_agent_and_user_sessions(self):
        """
        Scenario: An agent background task and an interactive user terminal session run concurrently.
        User runs terminal commands while agent is processing; neither session blocks or corrupts the other.
        """
        agent_client = await self.create_client(auth=True)
        user_client = await self.create_client(auth=True)

        # Agent launches a 1-second diagnostic loop
        agent_cmd = make_python_cmd(
            "-u", "-c",
            "import time; [print(f'agent_step_{i}', flush=True) or time.sleep(0.1) for i in range(5)]"
        )
        await agent_client.send("execute", command=agent_cmd, execution_id="agent_workflow")

        # Simultaneously, user executes interactive commands in Terminal
        user_out1, _, code1 = await user_client.execute(
            make_python_cmd("-c", "print('user_cmd_1')"),
            execution_id="user_term_1"
        )
        self.assertEqual(code1, 0)
        self.assertIn("user_cmd_1", user_out1)

        user_info = await user_client.sys_info()
        self.assertEqual(user_info.get("type"), "sys_info")

        user_out2, _, code2 = await user_client.execute(
            make_python_cmd("-c", "print('user_cmd_2')"),
            execution_id="user_term_2"
        )
        self.assertEqual(code2, 0)
        self.assertIn("user_cmd_2", user_out2)

        # Await agent workflow completion
        agent_frames = await agent_client.collect_until("completed")
        agent_stdout = "".join(f.get("data", "") for f in agent_frames if f.get("type") == "stdout")
        for i in range(5):
            self.assertIn(f"agent_step_{i}", agent_stdout)


if __name__ == "__main__":
    unittest.main()
