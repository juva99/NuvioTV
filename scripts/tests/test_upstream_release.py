from __future__ import annotations

import unittest
from pathlib import Path

from upstream_release import conflict_pr_body, next_version_code, plan_release


def release(tag, date, **overrides):
    return {
        "tag_name": tag, "published_at": date, "draft": False,
        "prerelease": False, "body": "", "html_url": f"https://example.com/{tag}",
        **overrides,
    }


class UpstreamReleaseTests(unittest.TestCase):
    def setUp(self):
        self.baseline = release("1.0.0", "2026-09-19T00:00:00Z")
        self.fork = [release("v0.9.0-beta-subtitle-sync.24", "2026-09-29T00:00:00Z")]

    def plan(self, upstream, fork=None):
        return plan_release(
            [self.baseline, *upstream], self.fork if fork is None else fork,
            "NuvioMedia/NuvioTV", "1.0.0",
        )

    def test_skips_baseline_older_releases_prereleases_and_drafts(self):
        self.assertEqual(self.plan([
            release("0.9.9", "2026-09-18T00:00:00Z"),
            release("1.1.0-beta.2", "2026-09-25T00:00:00Z"),
            release("1.1.0", "2026-09-26T00:00:00Z", prerelease=True),
            release("1.2.0", "2026-09-27T00:00:00Z", draft=True),
        ]), {"pending": "false"})

    def test_keeps_upstream_tag_and_advances_legacy_version_code(self):
        plan = self.plan([release("1.1.0", "2026-10-01T00:00:00Z")])
        self.assertEqual(plan["release_tag"], "1.1.0")
        self.assertEqual(plan["version_code"], "2025")
        self.assertIn("<!-- nuvio-upstream-release:1.1.0 -->", plan["release_notes"])
        self.assertIn("<!-- nuvio-fork-version-code:2025 -->", plan["release_notes"])
        self.assertIn("upstream AutoSync unchanged", plan["release_notes"])

    def test_beta_transition_baseline_does_not_publish_older_stable(self):
        self.baseline = release(
            "1.1.0-beta.5", "2026-10-07T00:00:00Z", prerelease=True
        )
        self.assertEqual(
            plan_release(
                [release("1.0.0", "2026-09-19T00:00:00Z"), self.baseline],
                self.fork, "NuvioMedia/NuvioTV", "1.1.0-beta.5",
            ),
            {"pending": "false"},
        )
        plan = plan_release(
            [
                self.baseline,
                release("1.1.0-beta.6", "2026-10-08T00:00:00Z", prerelease=True),
                release("1.1.0", "2026-10-09T00:00:00Z"),
            ],
            self.fork, "NuvioMedia/NuvioTV", "1.1.0-beta.5",
        )
        self.assertEqual(plan["upstream_tag"], "1.1.0")

    def test_new_release_and_draft_markers_reserve_version_codes(self):
        fork = [
            *self.fork,
            release("1.1.0", "2026-10-01T00:00:00Z",
                    body="<!-- nuvio-fork-version-code:2025 -->"),
            release("1.1.1", "2026-10-02T00:00:00Z", draft=True,
                    body="<!-- nuvio-fork-version-code:2026 -->"),
        ]
        self.assertEqual(next_version_code(fork), 2027)

    def test_existing_unmarked_tag_fails_instead_of_reusing_release(self):
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.plan(
                [release("1.1.0", "2026-10-01T00:00:00Z")],
                [*self.fork, release("1.1.0", "2026-10-01T00:00:00Z", draft=True)],
            )

    def test_processes_missed_releases_oldest_first(self):
        plan = self.plan([
            release("1.2.0", "2026-10-02T00:00:00Z"),
            release("1.1.0", "2026-10-01T00:00:00Z"),
        ])
        self.assertEqual(plan["upstream_tag"], "1.1.0")

    def test_completed_release_is_not_republished(self):
        self.fork[0]["body"] = "<!-- nuvio-upstream-release:1.1.0 -->"
        self.assertEqual(
            self.plan([release("1.1.0", "2026-10-01T00:00:00Z")]),
            {"pending": "false"},
        )

    def test_draft_does_not_mark_upstream_as_completed(self):
        self.fork[0].update(draft=True, body="<!-- nuvio-upstream-release:1.1.0 -->")
        self.assertEqual(
            self.plan([release("1.1.0", "2026-10-01T00:00:00Z")])["pending"], "true"
        )

    def test_missing_baseline_or_beta_history_fails_explicitly(self):
        with self.assertRaises(ValueError):
            plan_release([], self.fork, "NuvioMedia/NuvioTV", "1.0.0")
        with self.assertRaises(ValueError):
            self.plan([release("1.1.0", "2026-10-01T00:00:00Z")], [])

    def test_conflict_pr_preserves_template_and_records_unrun_checks(self):
        template = (
            Path(__file__).resolve().parents[2] / ".github" / "PULL_REQUEST_TEMPLATE.md"
        ).read_text(encoding="utf-8")
        body = conflict_pr_body(
            template, "1.1.0", ["app/example.kt"], "https://github.com/juva99/NuvioTV/issues/14"
        )
        self.assertEqual(
            [line for line in template.splitlines() if line.startswith("## ")],
            [line for line in body.splitlines() if line.startswith("## ")],
        )
        for line in template.splitlines():
            if line.startswith("<!--") or line.startswith("- ["):
                self.assertIn(line.replace("- [ ] ", "- [x] "), body.replace("- [ ] ", "- [x] "))
        self.assertIn("- `app/example.kt`", body)
        self.assertIn("No APK build or player/updater tests were run", body)
        self.assertIn("- [x] Approved larger or directional change", body)


if __name__ == "__main__":
    unittest.main()
