---
name: generate-release
description: Create, validate, dispatch, and verify a NuvioTV Android release. Use when asked to generate a new release, publish a beta, dispatch the Android release workflow, or prepare release notes and artifacts.
---

# Generate Release

Use this skill for release work in this repository. It is intentionally safe by default: inspect first, validate locally, use the existing GitHub Actions workflow, and never create a tag or release manually when the workflow is available.

## Repository Release Paths

- Subtitle-sync beta releases use `.github/workflows/subtitle-sync-beta-release.yml`.
- Standard Android releases use `.github/workflows/android-release.yml`.
- Release metadata and validation live in `scripts/release-metadata.sh`, `scripts/generate-release-notes.sh`, and `scripts/tests/`.
- Android version defaults and release build configuration live in `app/build.gradle.kts`.
- Release APKs are produced by Gradle and published by GitHub Actions. Do not commit generated APKs or release-note output.

## Preconditions

1. Confirm the working tree is clean unless the user explicitly asks to release a specific dirty revision.
2. Confirm the target branch/ref and commit SHA.
3. Confirm `gh auth status` succeeds and the remote repository is the intended repository.
4. Inspect existing tags and releases before choosing a version or tag.
5. Never print, read, or commit signing keys, GitHub tokens, local properties, or secret values.

Use PowerShell on Windows. Prefer these commands:

```powershell
git status --short --branch
```

## Subtitle Beta Release

The dispatch inputs are required:

- `release_tag`: use the next unused tag, normally `v<version>-beta-subtitle-sync.<n>`.
- `release_title`: human-readable GitHub release title.
- `version_code`: integer greater than the last published Android version code.
- `release_notes`: Markdown release notes.

Before dispatching:

1. Find the latest subtitle-sync release and tag.
2. Find the last successful workflow run and inspect its `NUVIO_VERSION_CODE` in the logs if the version code is not obvious.
3. Verify the selected tag does not exist locally or remotely.
4. Summarize user-visible changes from the commits since the previous release. Do not expose secrets, tokens, subtitle URLs with credentials, or internal implementation noise.
5. Dispatch only after the user requested publishing or explicitly approved the exact release inputs.

Example:

```powershell
  --repo OWNER/REPO `
  --ref dev `
  -f release_tag=v0.9.0-beta-subtitle-sync.17 `
  -f release_title="NuvioTV 0.9.0 beta - Subtitle Sync Test 17" `
  -f version_code=2017 `
  -f release_notes="$(Get-Content release-notes.md -Raw)"
```

The workflow checks out the selected ref, prepares secrets on the runner, runs subtitle synchronization tests, builds benchmark APKs, verifies signing and native libraries, and creates the prerelease. Do not bypass those checks with `gh release create` unless the workflow is unavailable and the user explicitly approves a manual fallback.

## Standard Android Release

Use `android-release.yml` for normal releases. It supports `dry-run`, `draft`, and `publish`.

1. Run the workflow's release-note validation or use the equivalent local scripts first:

```powershell
bash -n scripts/generate-release-notes.sh scripts/release-metadata.sh
py -3 -m unittest discover -s tests -v
```

Run the Python command from the `scripts` directory, or set `PYTHONPATH=scripts` when running from the repository root.

2. Dispatch `dry-run` first when release metadata, notes, or the target is uncertain.
3. Dispatch `draft` or `publish` only after inspecting the dry-run output and confirming the generated tag, version, notes, and commit.

## Monitoring

After dispatch, record the workflow URL and monitor it:

```powershell
```

On failure, report the failed job and actionable log excerpt. Do not rerun automatically when the failure indicates missing secrets, signing configuration, invalid versioning, or a source/test failure. Fix the cause, push a new commit, and dispatch a new release only after confirming the tag remains unused.

After success, verify the release and all expected APK assets:

```powershell
gh api repos/OWNER/REPO/releases/tags/RELEASE_TAG --jq '.target_commitish, .prerelease, .assets[].name'
```

The subtitle beta workflow should publish five benchmark APKs: arm64-v8a, armeabi-v7a, universal, x86, and x86_64.

## Safety Rules

- Never force-push, reset, amend, or delete a release/tag as part of normal release generation.
- Never publish from a commit other than the reviewed/pushed commit.
- Never reuse an existing tag or version code.
- Never put secrets in release notes, issue bodies, logs, or commits.
- Preserve the repository's existing workflow and signing checks.
- If a required input, secret, permission, or version is ambiguous, stop and ask the user instead of guessing.

## Completion Report

Report:

- Commit SHA released.
- Release tag, title, and URL.
- Workflow run URL and final conclusion.
- APK asset names verified.
- Tests and validation performed.
- Any warnings or skipped checks.
