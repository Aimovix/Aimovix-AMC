"""Exercise service stop against real delayed and unreaped processes."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

CLI = Path(__file__).with_name('amc').resolve()


@unittest.skipUnless(sys.platform.startswith('linux'), 'Requires Linux /proc')
class ServiceStopTests(unittest.TestCase):
    def stop_process(self, delay, reap=False):
        with tempfile.TemporaryDirectory() as home:
            directory = Path(home) / '.termux_agent'
            directory.mkdir()
            daemon = subprocess.Popen([sys.executable, '-c', '''
import signal, sys, time
signal.signal(signal.SIGTERM, lambda *_: (time.sleep(float(sys.argv[1])), sys.exit(0)))
print('ready', flush=True)
while True: time.sleep(1)
''', str(delay)], stdout=subprocess.PIPE, text=True)
            try:
                self.assertEqual(daemon.stdout.readline().strip(), 'ready')
                (directory / 'daemon.pid').write_text(str(daemon.pid))
                if reap:
                    threading.Thread(target=daemon.wait, daemon=True).start()
                result = subprocess.run(['bash', str(CLI), 'stop'],
                    env={**os.environ, 'HOME': home, 'PREFIX': '/usr'},
                    capture_output=True, text=True, timeout=15)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('AMC bridge stopped.', result.stdout)
                self.assertFalse((directory / 'daemon.pid').exists())
                self.assertEqual(daemon.wait(timeout=1), 0)
            finally:
                if daemon.poll() is None:
                    daemon.kill()
                daemon.wait()
                daemon.stdout.close()

    def test_stop_allows_cleanup_longer_than_five_seconds(self):
        self.stop_process(6, reap=True)

    @unittest.skipUnless(Path("/proc/self/stat").is_file(), "Requires visible procfs")
    def test_stop_accepts_unreaped_exited_process(self):
        self.stop_process(0)
