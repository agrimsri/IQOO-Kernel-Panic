"""Behavioral tests using disposable Git history, never the team's repository."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "check-commit-times.sh"
BOUNDARY = "2026-10-09T19:00:00+05:30"
EARLY = "2026-10-09T18:59:59+05:30"
LATER = "2026-10-10T01:00:00+05:30"


class CommitTimesTest(unittest.TestCase):
    def setUp(self):
        self.bash = os.environ.get("TRACEVEIL_BASH") or shutil.which("bash")
        if not self.bash:
            self.fail("Bash is required; Windows users can set TRACEVEIL_BASH to Git Bash")
        self.temp = tempfile.TemporaryDirectory(prefix="traceveil-commit-times-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        self.env["GIT_CONFIG_NOSYSTEM"] = "1"
        self.git("init", "--initial-branch=main", str(self.repo), cwd=self.root)
        self.git("config", "user.name", "TraceVeil synthetic test fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "core.hooksPath", str(self.root / "disabled-hooks"))

    def git(self, *arguments, cwd=None, dates=None):
        env = self.env.copy()
        if dates:
            env.update(GIT_AUTHOR_DATE=dates[0], GIT_COMMITTER_DATE=dates[1])
        result = subprocess.run(
            ["git", *arguments], cwd=cwd or self.repo, env=env,
            capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def commit(self, authored=BOUNDARY, committed=BOUNDARY):
        self.git("commit", "--allow-empty", "-m", "synthetic test fixture", dates=(authored, committed))
        return self.git("rev-parse", "HEAD")

    def check(self, *arguments, cwd=None):
        return subprocess.run(
            [self.bash, str(SCRIPT), *arguments], cwd=cwd or self.repo,
            env=self.env, capture_output=True, text=True, timeout=30,
        )

    def raw_commit(self, author_date, committer_date):
        parent = self.commit()
        tree = self.git("rev-parse", "HEAD^{tree}")
        payload = (
            f"tree {tree}\nparent {parent}\n"
            f"author Synthetic fixture <fixture@example.invalid> {author_date}\n"
            f"committer Synthetic fixture <fixture@example.invalid> {committer_date}\n"
            "\nSynthetic malformed-metadata fixture\n"
        )
        result = subprocess.run(
            ["git", "hash-object", "--literally", "-t", "commit", "-w", "--stdin"],
            input=payload.encode("utf-8"), cwd=self.repo, env=self.env,
            capture_output=True, timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stderr.decode("utf-8"))
        sha = result.stdout.decode("ascii").strip()
        self.git("update-ref", "refs/heads/main", sha)
        return sha

    def test_accepts_exact_cutoff_and_later_history(self):
        self.commit()
        self.commit(LATER, LATER)
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("PASS: checked 2 reachable commits", result.stdout)

    def test_rejects_author_one_second_before_cutoff(self):
        sha = self.commit(EARLY, LATER)
        result = self.check()
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn(sha, result.stderr)
        self.assertIn("author", result.stderr)
        self.assertNotIn("PASS:", result.stdout)

    def test_rejects_committer_one_second_before_cutoff(self):
        sha = self.commit(LATER, EARLY)
        result = self.check()
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn(sha, result.stderr)
        self.assertIn("committer", result.stderr)

    def test_reports_both_early_dates_without_stopping_at_first(self):
        sha = self.commit(EARLY, EARLY)
        result = self.check()
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn(sha, result.stderr)
        self.assertIn("author", result.stderr)
        self.assertIn("committer", result.stderr)

    def test_checks_early_ancestor_not_only_latest_commit(self):
        ancestor = self.commit(EARLY, EARLY)
        self.commit(LATER, LATER)
        result = self.check()
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn(ancestor, result.stderr)

    def test_checks_merged_side_branch_ancestry(self):
        self.commit()
        self.git("switch", "-c", "side")
        early_side = self.commit(EARLY, LATER)
        self.git("switch", "main")
        self.commit(LATER, LATER)
        self.git("merge", "--no-ff", "side", "-m", "synthetic merge", dates=(LATER, LATER))
        result = self.check()
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn(early_side, result.stderr)

    def test_does_not_check_unreachable_branch(self):
        self.commit()
        self.git("switch", "-c", "unmerged")
        self.commit(EARLY, EARLY)
        self.git("switch", "main")
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("PASS: checked 1 reachable commits", result.stdout)

    def test_can_check_explicit_revision_without_later_descendants(self):
        approved_revision = self.commit()
        self.commit(EARLY, EARLY)
        result = self.check(approved_revision)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("PASS: checked 1 reachable commits", result.stdout)

    def test_rejects_shallow_history_even_when_visible_commit_is_valid(self):
        self.commit(EARLY, EARLY)
        self.commit(LATER, LATER)
        shallow = self.root / "shallow"
        self.git("clone", "--depth=1", self.repo.as_uri(), str(shallow), cwd=self.root)
        result = self.check(cwd=shallow)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("shallow", result.stderr.lower())
        self.assertNotIn("PASS:", result.stdout)

    def test_rejects_unknown_revision(self):
        self.commit()
        result = self.check("no-such-revision")
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("revision", result.stderr.lower())
        self.assertNotIn("PASS:", result.stdout)

    def test_rejects_option_as_revision(self):
        self.commit()
        result = self.check("--help")
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertNotIn("PASS:", result.stdout)

    def test_rejects_directory_outside_git(self):
        result = self.check(cwd=self.root)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("repository", result.stderr.lower())

    def test_rejects_extra_arguments(self):
        self.commit()
        result = self.check("HEAD", "main")
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("Usage:", result.stderr)

    def test_rejects_malformed_author_timestamp_metadata(self):
        for date in ("garbage +0000", "", "-1 +0000", "999999999999999999999999 +0000"):
            with self.subTest(date=date):
                sha = self.raw_commit(date, "1791552600 +0530")
                result = self.check()
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn(sha, result.stderr)
                self.assertNotIn("PASS:", result.stdout)

    def test_rejects_malformed_committer_timestamp_metadata(self):
        for date in ("garbage +0000", "", "-1 +0000", "999999999999999999999999 +0000"):
            with self.subTest(date=date):
                sha = self.raw_commit("1791552600 +0530", date)
                result = self.check()
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn(sha, result.stderr)
                self.assertNotIn("PASS:", result.stdout)


if __name__ == "__main__":
    unittest.main()
