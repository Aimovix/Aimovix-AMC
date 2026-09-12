"""A failed download must leave the installed bridge intact."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class InstallerTests(unittest.TestCase):
    def test_partial_download_does_not_replace_installed_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / '.termux_agent'
            target.mkdir()
            names = ('bridge_daemon.py', 'amc', 'requirements.txt', 'local_model_manager.sh')
            for name in names:
                (target / name).write_text('original-' + name)
            script = root / 'setup.sh'
            shutil.copyfile(Path(__file__).with_name('setup.sh'), script)
            prefix = root / 'prefix'
            bin_dir = prefix / 'bin'
            bin_dir.mkdir(parents=True)
            for name, body in {
                'pkg': 'exit 0',
                'curl': '''while [ "$1" != "-o" ]; do shift; done
shift
case "$1" in
    */bridge_daemon.py) echo replacement > "$1" ;;
    *) echo partial > "$1"; exit 22 ;;
esac''',
            }.items():
                command = bin_dir / name
                command.write_text('#!/bin/sh\n' + body + '\n')
                command.chmod(0o700)
            result = subprocess.run(['bash', str(script)],
                env={**os.environ, 'HOME': str(root), 'PREFIX': str(prefix)},
                capture_output=True, text=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)
            for name in names:
                self.assertEqual((target / name).read_text(), 'original-' + name)
            self.assertEqual(list(target.glob('.install.*')), [])
