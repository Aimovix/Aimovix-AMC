"""
Aimovix-AMC Unified E2E Test Runner.
Executes opaque-box E2E test suites with CLI tier filtering, feature filtering,
test discovery, and diagnostic summary reporting.

Usage:
  python tests/e2e/runner.py --all
  python tests/e2e/runner.py --tier 1
  python tests/e2e/runner.py --tier 2
  python tests/e2e/runner.py --tier 3
  python tests/e2e/runner.py --tier 4
  python tests/e2e/runner.py --feature 1
  python tests/e2e/runner.py --list
"""

import argparse
import os
import sys
import time
import unittest

# Ensure project root is on sys.path
PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
if PROJECT_ROOT not in sys.path:
    sys.path.insert(0, PROJECT_ROOT)


def build_suite(tier: int = 0, feature: int = 0) -> unittest.TestSuite:
    loader = unittest.defaultTestLoader
    suite = unittest.TestSuite()

    tier_modules = {
        1: "tests.e2e.test_tier1_features",
        2: "tests.e2e.test_tier2_boundaries",
        3: "tests.e2e.test_tier3_combinations",
        4: "tests.e2e.test_tier4_scenarios",
        5: "tests.e2e.test_adversarial_tier5",
    }

    selected_tiers = [tier] if tier in tier_modules else [1, 2, 3, 4, 5]

    for t in selected_tiers:
        mod_name = tier_modules[t]
        try:
            mod = __import__(mod_name, fromlist=["*"])
            tier_suite = loader.loadTestsFromModule(mod)
            if feature > 0:
                feat_pattern = f"feat{feature}_"
                for test in _flatten_suite(tier_suite):
                    if feat_pattern in test.id():
                        suite.addTest(test)
            else:
                suite.addTests(tier_suite)
        except ImportError as e:
            print(f"[Warning] Could not load {mod_name}: {e}", file=sys.stderr)

    return suite


def _flatten_suite(suite):
    tests = []
    for item in suite:
        if isinstance(item, unittest.TestSuite):
            tests.extend(_flatten_suite(item))
        else:
            tests.append(item)
    return tests


def main():
    parser = argparse.ArgumentParser(description="Aimovix-AMC E2E Test Runner")
    parser.add_argument("--tier", type=int, choices=[1, 2, 3, 4, 5], default=0, help="Run specific tier (1-5)")
    parser.add_argument("--feature", type=int, choices=range(1, 21), default=0, help="Run specific feature (1-20)")
    parser.add_argument("--all", action="store_true", help="Run all 5 tiers")
    parser.add_argument("--list", action="store_true", help="List all discovered tests without running")
    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose test execution output")
    parser.add_argument("--benchmark", action="store_true", help="Output execution benchmark timing metrics")

    args = parser.parse_args()

    suite = build_suite(tier=args.tier, feature=args.feature)
    all_tests = _flatten_suite(suite)

    if args.list:
        print(f"=== Discovered {len(all_tests)} E2E Tests ===")
        for t in all_tests:
            print(f"  {t.id()}")
        return 0

    print("=" * 70)
    print(" AIMOVIX-AMC END-TO-END (E2E) TEST RUNNER")
    print("=" * 70)
    print(f"Filter: Tier={args.tier or 'ALL'} | Feature={args.feature or 'ALL'}")
    print(f"Discovered: {len(all_tests)} tests")
    print("-" * 70)

    start_time = time.time()
    runner = unittest.TextTestRunner(verbosity=2 if args.verbose else 1)
    result = runner.run(suite)
    duration = time.time() - start_time

    print("-" * 70)
    print(f"Executed: {result.testsRun} tests in {duration:.2f}s")
    print(f"Passed:   {result.testsRun - len(result.failures) - len(result.errors)}")
    print(f"Failed:   {len(result.failures)}")
    print(f"Errors:   {len(result.errors)}")
    print(f"Skipped:  {len(result.skipped)}")

    if args.benchmark:
        print("\n--- Benchmark Metrics ---")
        avg_ms = (duration / max(1, result.testsRun)) * 1000
        print(f"Average time per test: {avg_ms:.2f} ms")
        print(f"Total throughput: {result.testsRun / max(0.001, duration):.2f} tests/sec")

    if not result.wasSuccessful():
        sys.exit(1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
