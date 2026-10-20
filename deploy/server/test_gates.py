"""Offline negative tests for promotion trust boundaries. No network/Docker/process execution."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


gate = load("ci_gate", "verify-ci.py")
server = load("server_deploy", "deploy.py")
SHA = "a" * 40
DIGEST = "b" * 64


class PromotionGate(unittest.TestCase):
    def test_both_real_ci_profiles_and_required_job_rejections(self):
        for component, (repo, branch, names) in gate.PROFILES.items():
            run = dict(id=123, status="completed", conclusion="success", event="push",
                       head_repository={"full_name": repo}, head_branch=branch, head_sha=SHA,
                       path=".github/workflows/ci.yml")
            jobs = [dict(name=name, conclusion="success") for name in names]
            gate.validate(component, "123", SHA, run, jobs, SHA)
            for name in names:
                for result in ("failure", "skipped", "cancelled", None):
                    bad_jobs = copy.deepcopy(jobs)
                    next(job for job in bad_jobs if job["name"] == name)["conclusion"] = result
                    with self.subTest(component=component, name=name, result=result):
                        with self.assertRaises(AssertionError):
                            gate.validate(component, "123", SHA, run, bad_jobs, SHA)
            for field, value in (("event", "pull_request"), ("event", "workflow_dispatch"),
                                 ("head_repository", {"full_name": "attacker/fork"}),
                                 ("head_branch", "feature/evil"), ("head_sha", "c" * 40),
                                 ("path", ".github/workflows/pages.yml"), ("conclusion", "failure"),
                                 ("status", "in_progress"), ("id", 124)):
                bad = dict(run, **{field: value})
                with self.subTest(component=component, field=field, value=value):
                    with self.assertRaises(AssertionError):
                        gate.validate(component, "123", SHA, bad, jobs, SHA)
            for bad_jobs in (jobs[:-1], jobs + [jobs[0]]):
                with self.assertRaises(AssertionError):
                    gate.validate(component, "123", SHA, run, bad_jobs, SHA)
            with self.assertRaises(AssertionError):
                gate.validate(component, "123", SHA, run, jobs, "d" * 40)

    def test_receipt_rejects_mutable_image_other_repo_and_command_payloads(self):
        policy = {"backend": {"repository": "ghcr.io/owner/backend"}}
        valid = dict(image="ghcr.io/owner/backend@sha256:" + DIGEST, sha=SHA, ciRun="123")
        server.receipt("backend", valid, policy)
        for field, value in (("image", "ghcr.io/owner/backend:latest"),
                             ("image", "ghcr.io/attacker/image@sha256:" + DIGEST),
                             ("image", valid["image"] + "; id"), ("sha", "HEAD"),
                             ("sha", SHA + "\n"), ("ciRun", "123;id")):
            with self.subTest(field=field, value=value):
                with self.assertRaises(AssertionError):
                    server.receipt("backend", dict(valid, **{field: value}), policy)
        with self.assertRaises(AssertionError):
            server.receipt("backend", dict(valid, arbitrary="argument"), policy)

    def test_release_state_success_rollback_and_backend_freeze(self):
        # Simulate commands and flock; this verifies state transitions, not Linux lock execution.
        for outcome in ("success", "web-fails", "backend-fails", "rollback-fails", "bootstrap-fails"):
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                state = root / "state"
                state.mkdir()
                policy = dict(port=18180, backend=dict(repository="ghcr.io/owner/backend"),
                              web=dict(repository="ghcr.io/owner/web"))
                (root / "policy.json").write_text(json.dumps(policy))
                old = {component: dict(image=policy[component]["repository"] + "@sha256:" + DIGEST,
                                       sha=SHA, ciRun="123") for component in ("backend", "web")}
                if outcome != "bootstrap-fails":
                    (state / "current.json").write_text(json.dumps(old))
                initial = root / "initial.json"
                initial.write_text(json.dumps(old))
                component = "backend" if outcome in ("backend-fails", "bootstrap-fails") else "web"
                args = ["--remote", component, "sha256:" + "e" * 64, "f" * 40, "456"]
                if outcome == "bootstrap-fails":
                    args = ["bootstrap", str(initial)]
                calls = []

                def compose(manifest, policy, *command, **kwargs):
                    calls.append((copy.deepcopy(manifest), command))

                def check(*unused):
                    if outcome in ("backend-fails", "bootstrap-fails", "rollback-fails"):
                        raise RuntimeError("simulated HTTP failure")
                    if outcome == "web-fails" and len(calls) == 1:
                        raise RuntimeError("simulated initial web failure")

                fake_fcntl = types.SimpleNamespace(LOCK_EX=2, flock=lambda *args: None)
                old_umask = os.umask(0o077)
                try:
                    with patch.dict(sys.modules, fcntl=fake_fcntl), \
                         patch.object(server.os, "geteuid", return_value=0, create=True), \
                         patch.multiple(server, ROOT=root, STATE=state, POLICY=root / "policy.json", LOCK=root / "lock"), \
                         patch.object(server, "pull"), patch.object(server, "compose", side_effect=compose), \
                         patch.object(server, "healthy"), patch.object(server, "check_http", side_effect=check):
                        if outcome == "success":
                            server.deploy(args)
                        else:
                            with self.assertRaises(RuntimeError):
                                server.deploy(args)
                finally:
                    os.umask(old_umask)
                blocked = (state / "BLOCKED.json").exists()
                self.assertEqual(blocked, outcome in ("backend-fails", "rollback-fails", "bootstrap-fails"))
                self.assertEqual((state / "IN_PROGRESS.json").exists(), blocked)
                if outcome == "success":
                    current = json.loads((state / "current.json").read_text())
                    self.assertEqual(current["backend"], old["backend"])
                    self.assertEqual(current["web"]["sha"], "f" * 40)
                elif outcome == "bootstrap-fails":
                    self.assertFalse((state / "current.json").exists())
                else:
                    self.assertEqual(json.loads((state / "current.json").read_text()), old)
                if outcome in ("web-fails", "rollback-fails"):
                    self.assertEqual(calls[-1][0], old)
                if outcome == "backend-fails":
                    self.assertEqual(len(calls), 2)  # no automatic backend rollback

    def test_old_port_policy_stops_before_any_docker_or_release_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "policy.json").write_text(json.dumps({"port": 18080}))
            fake_fcntl = types.SimpleNamespace(LOCK_EX=2, flock=lambda *args: None)
            old_umask = os.umask(0o077)
            try:
                with patch.dict(sys.modules, fcntl=fake_fcntl), \
                     patch.object(server.os, "geteuid", return_value=0, create=True), \
                     patch.multiple(server, STATE=root / "state", POLICY=root / "policy.json"), \
                     patch.object(server, "command") as command:
                    with self.assertRaisesRegex(AssertionError, "port must match"):
                        server.deploy(["bootstrap", str(root / "unused.json")])
                    command.assert_not_called()
                    self.assertFalse((root / "state").exists())
            finally:
                os.umask(old_umask)


if __name__ == "__main__":
    unittest.main()
