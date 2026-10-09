from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import uuid
from pathlib import Path


LEGACY_BETA_TAG = re.compile(r"v?[0-9]+\.[0-9]+\.[0-9]+-beta-subtitle-sync\.([0-9]+)")
STABLE_TAG = re.compile(r"v?[0-9]+\.[0-9]+\.[0-9]+")
VERSION_CODE_MARKER = re.compile(r"<!-- nuvio-fork-version-code:([0-9]+) -->")


def next_version_code(fork_releases: list[dict]) -> int:
    version_codes = [
        2000 + int(match.group(1))
        for fork in fork_releases
        if (match := LEGACY_BETA_TAG.fullmatch(fork["tag_name"]))
    ]
    version_codes.extend(
        int(match.group(1))
        for fork in fork_releases
        for match in VERSION_CODE_MARKER.finditer(fork.get("body") or "")
    )
    if not version_codes:
        raise ValueError("No fork version-code history found to establish upgrade-safe versioning")
    return max(version_codes) + 1


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
        (
            release for release in upstream_releases
            if not release["draft"] and release["tag_name"] == baseline_tag
        ),
        None,
    )
    if baseline is None:
        raise ValueError(f"Upstream release baseline is missing: {baseline_tag}")

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
    version_code = next_version_code(fork_releases)
    tag = release["tag_name"]
    if any(fork["tag_name"] == tag for fork in fork_releases):
        raise ValueError(f"Fork release tag already exists without a published upstream marker: {tag}")
    notes = (
        f"## Upstream stable {tag}\n\n"
        f"- Based on [{upstream} {tag}]({release['html_url']}) with upstream AutoSync unchanged.\n"
        "- Retains fork signing/updating, IntroDB/avatar defaults, RTL punctuation, "
        "app-language, and next-episode fixes.\n"
        "- Passed the focused player/updater tests, APK signing, and native-library checks.\n\n"
        f"<!-- nuvio-upstream-release:{tag} -->\n"
        f"<!-- nuvio-fork-version-code:{version_code} -->"
    )
    return {
        "pending": "true",
        "upstream_tag": tag,
        "release_tag": tag,
        "release_title": f"NuvioTV {tag} - Minimal Fork",
        "version_code": str(version_code),
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
        "UI / behavior impact": "Includes the upstream stable release's changes; use upstream "
        "AutoSync unchanged and preserve only the documented minimal fork fixes.",
        "Policy check": "This PR implements the approved upstream integration request.",
        "Scope boundaries": "Only upstream release integration and necessary conflict resolution; "
        "no unrelated fork cleanup or refactoring.",
        "Testing": "The automatic merge encountered conflicts. No APK build or player/updater tests "
        "were run for this merge. After resolution and merge, the release workflow runs "
        "player/updater tests and verifies five APKs, signatures, and native libraries before publishing. "
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
