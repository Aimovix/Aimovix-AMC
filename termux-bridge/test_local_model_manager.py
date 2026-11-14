"""local_model_manager.sh: the model download runs detached, reports progress, resumes, cancels."""
import hashlib
import http.server
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
import unittest

SCRIPT = Path(__file__).with_name('local_model_manager.sh')
MODEL = b'GGUF' + bytes(range(256)) * 4096  # ~1 MiB that starts like a GGUF file


class RangeHandler(http.server.BaseHTTPRequestHandler):
    """Serves MODEL with Range support; `delay` slows the body so a download can be caught mid-way."""
    delay = 0.0
    requests = []

    def log_message(self, *args):
        pass

    def _range(self):
        header = self.headers.get('Range', '')
        if header.startswith('bytes=') and header.endswith('-'):
            return int(header[6:-1])
        return 0

    def do_HEAD(self):
        self.send_response(200)
        self.send_header('Content-Length', str(len(MODEL)))
        self.end_headers()

    def do_GET(self):
        start = self._range()
        type(self).requests.append(start)
        body = MODEL[start:]
        self.send_response(206 if start else 200)
        if start:
            self.send_header('Content-Range', f'bytes {start}-{len(MODEL) - 1}/{len(MODEL)}')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        step = 64 * 1024
        for i in range(0, len(body), step):
            try:
                self.wfile.write(body[i:i + step])
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                return
            if self.delay:
                time.sleep(self.delay)


@unittest.skipUnless(shutil.which('bash') and shutil.which('curl'), 'needs bash and curl')
class LocalModelManagerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.home = root / 'home'
        self.home.mkdir()
        bin_dir = root / 'bin'
        bin_dir.mkdir()
        # llama.cpp is "installed": the runtime phase must not call pkg.
        fake = bin_dir / 'llama-server'
        fake.write_text('#!/bin/sh\necho usage\n')
        fake.chmod(0o755)
        self.env = dict(os.environ, HOME=str(self.home), PATH=f'{bin_dir}:{os.environ["PATH"]}',
                        AMC_MIN_GGUF_BYTES='1024')
        # The script resolves curl through https URLs only; plain http to localhost goes via a
        # tiny https shim is overkill, so tests call the internal worker with an http URL where needed.
        RangeHandler.delay = 0.0
        RangeHandler.requests = []
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), RangeHandler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f'http://127.0.0.1:{self.server.server_address[1]}/model.gguf'
        self.models = self.home / '.termux_agent' / 'models'

    def tearDown(self):
        self.run_script('cancel')
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def run_script(self, *args, timeout=30):
        result = subprocess.run(['bash', str(SCRIPT), *args], env=self.env, capture_output=True, text=True,
                                timeout=timeout, stdin=subprocess.DEVNULL)
        status = {}
        for line in result.stdout.splitlines():
            if line.startswith('status.') and '=' in line:
                key, _, value = line[7:].partition('=')
                status[key] = value
        return result, status

    def wait_for(self, phase, timeout=20):
        deadline = time.time() + timeout
        status = {}
        while time.time() < deadline:
            _, status = self.run_script('status')
            if status.get('install_phase') == phase:
                return status
            time.sleep(0.2)
        self.fail(f'phase {phase} not reached: {status}')

    def test_install_rejects_plain_http(self):
        result, status = self.run_script('install', '--model', 'm.gguf', '--url', self.url)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('https', status.get('error', ''))

    def test_worker_downloads_verifies_and_reports_done(self):
        sha = hashlib.sha256(MODEL).hexdigest()
        result, _ = self.run_script('_download', '--model', 'm.gguf', '--url', self.url, '--sha256', sha)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.models / 'm.gguf').read_bytes(), MODEL)
        _, status = self.run_script('status')
        self.assertEqual(status['install_phase'], 'done')
        self.assertEqual(status['install_model'], 'm.gguf')
        self.assertEqual(int(status['install_bytes']), len(MODEL))
        self.assertIn('m.gguf', status['models'])

    def test_checksum_mismatch_removes_the_file_and_reports_checksum(self):
        result, _ = self.run_script('_download', '--model', 'm.gguf', '--url', self.url, '--sha256', '0' * 64)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.models / 'm.gguf.part').exists())
        self.assertFalse((self.models / 'm.gguf').exists())
        _, status = self.run_script('status')
        self.assertEqual(status['install_phase'], 'error')
        self.assertEqual(status['install_error'], 'checksum')

    def test_a_partial_file_is_resumed_not_restarted(self):
        self.models.mkdir(parents=True)
        (self.models / 'm.gguf.part').write_bytes(MODEL[:300_000])
        result, _ = self.run_script('_download', '--model', 'm.gguf', '--url', self.url)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(RangeHandler.requests, [300_000])
        self.assertEqual((self.models / 'm.gguf').read_bytes(), MODEL)

    def test_background_download_reports_progress_and_can_be_cancelled(self):
        RangeHandler.delay = 0.2
        sha = hashlib.sha256(MODEL).hexdigest()
        # Start the detached worker the way `install` does, but with the local http URL.
        state_dir = self.home / '.termux_agent' / 'llm'
        proc = subprocess.Popen(['setsid', 'bash', str(SCRIPT), '_download', '--model', 'm.gguf', '--url', self.url,
                                 '--sha256', sha], env=self.env, stdin=subprocess.DEVNULL,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        state_dir.mkdir(parents=True, exist_ok=True)
        (state_dir / 'download.pid').write_text(str(proc.pid))
        status = self.wait_for('download')
        self.assertEqual(status['install_running'], '1')
        self.assertEqual(int(status['install_total']), len(MODEL))
        deadline = time.time() + 10
        while int(status['install_bytes']) == 0 and time.time() < deadline:
            time.sleep(0.2)
            _, status = self.run_script('status')
        self.assertGreater(int(status['install_bytes']), 0)

        _, status = self.run_script('cancel')
        proc.wait(timeout=10)
        self.assertEqual(status['install_phase'], 'cancelled')
        self.assertEqual(status['install_running'], '0')
        self.assertTrue((self.models / 'm.gguf.part').exists(), 'the partial file is kept for resuming')

    def test_a_dead_worker_is_reported_as_interrupted(self):
        state_dir = self.home / '.termux_agent' / 'llm'
        state_dir.mkdir(parents=True)
        (state_dir / 'install.state').write_text('PHASE=download\nMODEL=m.gguf\nTOTAL=10\nERROR=\nUPDATED=0\n')
        (state_dir / 'download.pid').write_text('999999')
        _, status = self.run_script('status')
        self.assertEqual(status['install_phase'], 'interrupted')

    def test_delete_removes_model_and_partial_file(self):
        self.models.mkdir(parents=True)
        (self.models / 'm.gguf').write_bytes(MODEL)
        (self.models / 'm.gguf.part').write_bytes(b'x')
        _, status = self.run_script('delete', '--model', 'm.gguf')
        self.assertEqual(status['deleted'], 'm.gguf')
        self.assertFalse((self.models / 'm.gguf').exists())
        self.assertFalse((self.models / 'm.gguf.part').exists())

    def test_install_of_a_present_model_returns_at_once(self):
        self.models.mkdir(parents=True)
        (self.models / 'm.gguf').write_bytes(MODEL)
        started = time.time()
        result, status = self.run_script('install', '--model', 'm.gguf', '--url', 'https://example.invalid/m.gguf')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertLess(time.time() - started, 5)
        self.assertEqual(status['install_phase'], 'done')
        self.assertEqual(status['installed'], 'm.gguf')


if __name__ == '__main__':
    unittest.main()
