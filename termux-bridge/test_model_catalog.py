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
        """A Hub that matches the catalog as it is now (later edits to the catalog do not change it)."""
        repos = {}
        for m in self.catalog["models"]:
            o, r, f = verify.URL_PATTERN.match(m["url"]).groups()
            repos.setdefault((o, r), {})[f] = {
                "size": (sizes or {}).get(f, m["size_bytes"]),
                "sha256": (shas or {}).get(f, m["sha256"] or "a" * 64),
            }
        return lambda owner, repo: repos.get((owner, repo), {})

    def test_check_passes_with_matching_hub_and_write_fills_checksums(self):
        self.assertEqual(verify.check(self.catalog, fetch=self.fake_hub()), [])
        for m in self.catalog["models"]:
            m["sha256"] = ""
        hub = self.fake_hub(shas={m["file"]: "c" * 64 for m in self.catalog["models"]})
        verify.check(self.catalog, fetch=hub, write=True)
        self.assertTrue(all(m["sha256"] == "c" * 64 for m in self.catalog["models"]))

    def test_shipped_entries_are_verified(self):
        for m in self.catalog["models"]:
            self.assertRegex(m["sha256"], r"^[0-9a-f]{64}$", f"{m['id']} has no checksum")

    def test_wrong_size_missing_file_and_bad_checksum_are_reported(self):
        first = self.catalog["models"][0]
        hub = self.fake_hub()
        problems = verify.check(self.catalog, fetch=self.fake_hub(sizes={first["file"]: 1}))
        self.assertTrue(any("size_bytes" in p for p in problems))
        first["sha256"] = "b" * 64
        self.assertTrue(any("sha256 does not match" in p for p in verify.check(self.catalog, fetch=hub)))
        first["file"] = "other.gguf"
        self.assertTrue(any("does not match the URL" in p for p in verify.check(self.catalog, fetch=hub)))

    def test_non_hub_urls_are_rejected(self):
        hub = self.fake_hub()
        self.catalog["models"][0]["url"] = "https://example.com/x.gguf"
        self.assertTrue(any("URL must be" in p for p in verify.check(self.catalog, fetch=hub)))

if __name__ == "__main__":
    unittest.main()
