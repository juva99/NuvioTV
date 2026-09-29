from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import uuid
from pathlib import Path


BETA_TAG = re.compile(r"v0\.9\.0-beta-subtitle-sync\.([0-9]+)")
STABLE_TAG = re.compile(r"v?[0-9]+\.[0-9]+\.[0-9]+")


def plan_release(
    upstream_releases: list[dict],
    fork_releases: list[dict],
    upstream: str,
    baseline_tag: str,
) -> dict:
    stable = [
        release for release in upstream_releases
        if not release["draft"] and not release["prerelease"]
        and STABLE_TAG.fullmatch(release["tag_name"])
    ]
    baseline = next(
        (release for release in stable if release["tag_name"] == baseline_tag), None
    )
    if baseline is None:
        raise ValueError(f"Upstream stable baseline is missing: {baseline_tag}")

    published = [release for release in fork_releases if not release["draft"]]
    pending = [
        release for release in stable
        if release["published_at"] > baseline["published_at"]
        and not any(
            f"<!-- nuvio-upstream-release:{release['tag_name']} -->"
            in (fork.get("body") or "")
            for fork in published
        )
    ]
    if not pending:
        return {"pending": "false"}

    release = min(pending, key=lambda item: item["published_at"])
    sequences = [
        int(match.group(1))
        for fork in fork_releases
        if (match := BETA_TAG.fullmatch(fork["tag_name"]))
    ]
    if not sequences:
        raise ValueError("No existing subtitle-sync beta found to establish versioning")
    sequence = max(sequences) + 1
    tag = release["tag_name"]
    notes = (
        f"## Upstream stable {tag}\n\n"
        f"- Merged [{upstream} {tag}]({release['html_url']}) into this fork's dev branch.\n"
        "- Retains this fork's Subtitle Sync V2 and existing development changes.\n"
        "- Passed the subtitle synchronization tests, APK signing, and native-library checks.\n\n"
        "This subtitle-sync build remains a prerelease and may include development changes "
        "beyond the upstream stable release.\n\n"
        f"<!-- nuvio-upstream-release:{tag} -->"
    )
    return {
        "pending": "true",
        "upstream_tag": tag,
        "release_tag": f"v0.9.0-beta-subtitle-sync.{sequence}",
        "release_title": f"NuvioTV Subtitle Sync V2 Test {sequence} - Upstream {tag}",
        "version_code": str(2000 + sequence),
        "release_notes": notes,
    }


def github_api(endpoint: str, paginate: bool = False):
    command = ["gh", "api", endpoint]
    if paginate:
        command += ["--paginate", "--slurp"]
    result = subprocess.run(
        command, check=True, capture_output=True, text=True, encoding="utf-8"
    )
    data = json.loads(result.stdout)
    return [item for page in data for item in page] if paginate else data


def conflict_pr_body(template: str, tag: str, conflicts: list[str], approval_url: str) -> str:
    details = {
        "Summary": f"Merge upstream stable `{tag}` into `dev`, preserving fork changes.",
        "PR type": "Maintainer-approved upstream release integration.",
        "Why": "The automatic merge found conflicts requiring maintainer resolution:\n\n"
        + "\n".join(f"- `{path}`" for path in conflicts),
        "Issue or approval": f"Approved fork release automation: {approval_url}",
        "Reproduction steps": "Not a bug-fix PR. Fetch the upstream release tag and merge it "
        "into dev to reproduce these merge conflicts.",
        "UI / behavior impact": "Includes the upstream stable release's changes; preserve "
        "this fork's subtitle synchronization when resolving conflicts.",
        "Policy check": "This PR implements the approved upstream integration request.",
        "Scope boundaries": "Only upstream release integration and necessary conflict resolution; "
        "no unrelated fork cleanup or refactoring.",
        "Testing": "The automatic merge encountered conflicts. No APK build or subtitle tests "
        "were run for this merge. After resolution and merge, the release workflow runs "
        "subtitle tests and verifies five APKs, signatures, and native libraries before publishing. "
        "The reviewer should record any additional validation performed during resolution.",
        "Screenshots / Video": "Upstream changes may affect UI. Reviewer: attach screenshots "
        "for any UI changes made during conflict resolution.",
        "Breaking changes": "Not yet assessed; review upstream release changes and conflict "
        "resolutions before merging.",
        "Linked issues": f"Approved automation scope: {approval_url}",
    }
    checked = {
        "Approved larger or directional change",
        "UI change has explicit maintainer approval",
        "Behavior change has explicit maintainer approval",
    }
    in_policy = False
    lines = []
    for line in template.splitlines():
        if line.startswith("## "):
            section = line[3:]
            in_policy = section == "Policy check"
            lines.extend([line, "", details[section]])
            continue
        if line.startswith("- [ ] ") and (in_policy or line[6:] in checked):
            line = line.replace("- [ ] ", "- [x] ", 1)
        lines.append(line)
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    planner = commands.add_parser("plan")
    planner.add_argument("--repository", required=True)
    planner.add_argument("--upstream", required=True)
    planner.add_argument("--baseline-tag", required=True)
    pr = commands.add_parser("conflict-pr")
    pr.add_argument("--tag", required=True)
    pr.add_argument("--conflicts", required=True)
    pr.add_argument("--approval-url", required=True)
    args = parser.parse_args()
    if args.command == "conflict-pr":
        print(conflict_pr_body(
            (Path(".github") / "PULL_REQUEST_TEMPLATE.md").read_text(encoding="utf-8"),
            args.tag,
            Path(args.conflicts).read_text(encoding="utf-8").splitlines(),
            args.approval_url,
        ))
        return
    plan = plan_release(
        github_api(f"repos/{args.upstream}/releases?per_page=100", paginate=True),
        github_api(f"repos/{args.repository}/releases?per_page=100", paginate=True),
        args.upstream,
        args.baseline_tag,
    )
    if plan["pending"] == "true":
        tag = plan["upstream_tag"]
        ref = github_api(f"repos/{args.upstream}/git/ref/tags/{tag}")
        target = ref["object"]
        while target["type"] == "tag":
            target = github_api(
                f"repos/{args.upstream}/git/tags/{target['sha']}"
            )["object"]
        if target["type"] != "commit":
            raise ValueError(f"Upstream tag does not point to a commit: {tag}")
        plan["upstream_sha"] = target["sha"]
    print(json.dumps(plan, indent=2))
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as stream:
            for key, value in plan.items():
                delimiter = uuid.uuid4().hex
                stream.write(f"{key}<<{delimiter}\n{value}\n{delimiter}\n")


if __name__ == "__main__":
    main()
