"""The local model catalog: schema, both copies identical, and the Hub check (offline, with a fake Hub)."""
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("verify_model_catalog", ROOT / "scripts/verify_model_catalog.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class CatalogTests(unittest.TestCase):
    def setUp(self):
        self.texts = [p.read_text(encoding="utf-8") for p in verify.CATALOGS]
        self.catalog = json.loads(self.texts[0])

    def test_both_copies_are_identical(self):
        self.assertEqual(self.texts[0], self.texts[1])

    def test_every_entry_is_complete_and_points_to_the_hub(self):
        for m in self.catalog["models"]:
            for key in verify.REQUIRED:
                self.assertIn(key, m, m.get("id"))
            match = verify.URL_PATTERN.match(m["url"])
            self.assertIsNotNone(match, m["url"])
            self.assertEqual(match.group(3), m["file"])
            self.assertEqual(m["size_mb"], -(-m["size_bytes"] // (1024 * 1024)), m["id"])
            self.assertTrue(m["sha256"] == "" or len(m["sha256"]) == 64)
            self.assertIn(m["tier"], ("primary", "speed", "small", "experimental"))

    def fake_hub(self, sizes=None, shas=None):
        def fetch(owner, repo):
            files = {}
            for m in self.catalog["models"]:
                o, r, f = verify.URL_PATTERN.match(m["url"]).groups()
                if (o, r) == (owner, repo):
                    files[f] = {"size": (sizes or {}).get(f, m["size_bytes"]), "sha256": (shas or {}).get(f, "a" * 64)}
            return files
        return fetch

    def test_check_passes_with_matching_hub_and_write_fills_checksums(self):
        self.assertEqual(verify.check(self.catalog, fetch=self.fake_hub()), [])
        verify.check(self.catalog, fetch=self.fake_hub(), write=True)
        self.assertTrue(all(m["sha256"] == "a" * 64 for m in self.catalog["models"]))

    def test_wrong_size_missing_file_and_bad_checksum_are_reported(self):
        first = self.catalog["models"][0]
        problems = verify.check(self.catalog, fetch=self.fake_hub(sizes={first["file"]: 1}))
        self.assertTrue(any("size_bytes" in p for p in problems))
        first["sha256"] = "b" * 64
        problems = verify.check(self.catalog, fetch=self.fake_hub())
        self.assertTrue(any("sha256 does not match" in p for p in problems))
        first["file"] = "other.gguf"
        self.assertTrue(any("does not match the URL" in p for p in verify.check(self.catalog, fetch=self.fake_hub())))

    def test_non_hub_urls_are_rejected(self):
        self.catalog["models"][0]["url"] = "https://example.com/x.gguf"
        self.assertTrue(any("URL must be" in p for p in verify.check(self.catalog, fetch=self.fake_hub())))


if __name__ == "__main__":
    unittest.main()
