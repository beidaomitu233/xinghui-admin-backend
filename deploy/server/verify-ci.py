#!/usr/bin/env python3
"""Read-only GitHub gate. No PR/fork/workflow_dispatch/old-head promotion."""
import json
import os
import re
import subprocess
import sys

PROFILES = {
    "backend": ("beidaomitu233/xinghui-admin-backend", "master", [
        "Backend build and required tests", "Required services MySQL 5.7",
        "Required services MySQL 8.0", "Backend quality gate"]),
    "web": ("beidaomitu233/xinghui-admin-vue3", "main", [
        "Root and application tests", "Project typecheck", "Project lint",
        "Production application and runtime API", "Documentation completeness and build",
        "Frontend quality gate"]),
}


def validate(component, run_id, sha, run, jobs, head):
    repo, branch, required = PROFILES[component]
    assert re.fullmatch(r"[0-9]+", run_id), "invalid run ID"
    assert re.fullmatch(r"[0-9a-f]{40}", sha), "invalid source SHA"
    assert str(run["id"]) == run_id, "run mismatch"
    assert run["status"] == "completed" and run["conclusion"] == "success", "CI failed/incomplete"
    assert run["event"] == "push", "push CI required"
    assert run["head_repository"]["full_name"] == repo, "fork/repository mismatch"
    assert run["head_branch"] == branch and run["head_sha"] == sha, "source mismatch"
    assert run["path"] == ".github/workflows/ci.yml", "wrong workflow"
    assert head == sha, "branch advanced; await its own CI"
    for name in required:
        matches = [job for job in jobs if job["name"] == name]
        assert len(matches) == 1 and matches[0]["conclusion"] == "success", "missing/failed gate: " + name


def api(path, pages=False):
    args = ["gh", "api", path]
    if pages:
        args += ["--paginate", "--slurp"]
    return json.loads(subprocess.check_output(args, text=True))


def main():
    component, run_id, sha = sys.argv[1:]
    repo, branch, _ = PROFILES[component]
    assert os.environ["GITHUB_REPOSITORY"] == repo, "unexpected executing repository"
    run = api(f"repos/{repo}/actions/runs/{run_id}")
    pages = api(f"repos/{repo}/actions/runs/{run_id}/jobs?filter=latest&per_page=100", True)
    jobs = [job for page in pages for job in page["jobs"]]
    head = api(f"repos/{repo}/git/ref/heads/{branch}")["object"]["sha"]
    validate(component, run_id, sha, run, jobs, head)
    print(f"verified {repo}@{sha} CI {run_id}")


if __name__ == "__main__":
    main()
