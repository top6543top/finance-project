import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class ManifestRenderTest(unittest.TestCase):
    def run_render(self, image, host, output):
        env = dict(os.environ, ACCOUNT_IMAGE=image, ACCOUNT_PUBLIC_HOST=host)
        return subprocess.run([sys.executable, str(ROOT/'scripts/render_manifests.py'), '--output', str(output)],
                              env=env, capture_output=True, text=True)

    def test_rejects_mutable_image_without_writing_deployment(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)/'release.yaml'
            result = self.run_render('example.com/account:latest', 'account.example.com', output)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(output.exists())

    def test_rejects_host_injection(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)/'release.yaml'
            result = self.run_render('example.com/account@sha256:'+'a'*64, 'example.com\ninvalid: yes', output)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(output.exists())

    def test_renders_only_requested_digest_and_tls_host(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)/'release.yaml'
            digest = 'example.com/account@sha256:'+'a'*64
            result = self.run_render(digest, 'account.example.com', output)
            self.assertEqual(result.returncode, 0, result.stderr)
            rendered = output.read_text(encoding='utf-8')
            self.assertIn(digest, rendered)
            self.assertIn('account.example.com', rendered)
            self.assertNotIn('__IMAGE__', rendered)
            self.assertNotIn(':latest', rendered)

if __name__ == '__main__':
    unittest.main()
