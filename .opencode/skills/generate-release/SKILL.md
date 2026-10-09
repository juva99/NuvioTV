---
name: generate-release
description: Create, validate, dispatch, and verify a NuvioTV Android release. Use when asked to generate a new release, publish a beta, dispatch the Android release workflow, or prepare release notes and artifacts.
---

# Generate Release

Use this skill for release work in this repository. It is intentionally safe by default: inspect first, validate locally, use the existing GitHub Actions workflow, and never create a tag or release manually when the workflow is available.

## Repository Release Paths

- Signed fork releases use `.github/workflows/fork-release.yml`.
- `.github/workflows/upstream-stable-release.yml` follows published stable releases after the `1.1.0-beta.5` migration baseline.
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
6. Confirm the `TMDB_API_KEY` Actions secret exists by name; never print its value. Fork release builds refuse to publish without it.

Use PowerShell on Windows. Prefer these commands:

```powershell
git status --short --branch
```

## Fork Release

The dispatch inputs are required:

- `release_tag`: use a published upstream tag, for example `1.1.0` or, for an explicitly requested manual beta, `1.1.0-beta.5`.
- `release_title`: human-readable GitHub release title.
- `version_code`: integer greater than the last published Android version code.
- `release_notes`: Markdown release notes.

Before dispatching:

1. Find the latest fork release and published upstream tag. Do not import unreleased `dev` commits. Automatic tracking is stable-only; an explicitly requested manual release may use the latest numbered beta/RC tag.
2. Read the `nuvio-fork-version-code` release-note marker. Legacy subtitle-sync builds use `2000 + N`; the next fork code must exceed all previous codes.
3. Verify the selected tag does not exist on the fork remote. A local upstream tag with the same name is expected.
4. Summarize user-visible changes from the commits since the previous release. Do not expose secrets, tokens, subtitle URLs with credentials, or internal implementation noise.
5. Dispatch only after the user requested publishing or explicitly approved the exact release inputs.

Example:

```powershell
gh workflow run fork-release.yml `
  --repo OWNER/REPO `
  --ref dev `
  -f release_tag=1.1.0 `
  -f release_title="NuvioTV 1.1.0 - Minimal Fork" `
  -f version_code=2025 `
  -f release_notes="$(Get-Content release-notes.md -Raw)"
```

The workflow checks out the selected ref, runs upstream AutoSync and retained-fix tests, builds five benchmark APKs, verifies their package/version/signature/native library, and publishes a fork release with the appropriate stable/prerelease flag. Preserve the `nuvio-upstream-release` marker when following an upstream release; the workflow records `nuvio-fork-version-code`. Keep the legacy `NUVIO_SUBTITLE_BETA_*` signing secrets and `com.nuvio.tv.debug` package for installed APK compatibility. Do not bypass the workflow with a manual release unless explicitly approved.

Before the primary release, the workflow publishes the same universal APK in compatibility prereleases for every historical subtitle-sync prefix, discovered from Git tags even when older releases were deleted. Do not remove these entries: old clients require exact prefixes. Compatibility notes must not contain the `nuvio-upstream-release` completion marker; a partial failure retries with a higher code before completing the primary release.

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
gh run list --repo OWNER/REPO --workflow fork-release.yml --limit 5
gh run watch RUN_ID --repo OWNER/REPO --exit-status
```

On failure, report the failed job and actionable log excerpt. Do not rerun automatically when the failure indicates missing secrets, signing configuration, invalid versioning, or a source/test failure. Fix the cause, push a new commit, and dispatch a new release only after confirming the tag remains unused.

After success, verify the release and all expected APK assets:

```powershell
gh api repos/OWNER/REPO/releases/tags/RELEASE_TAG --jq '.target_commitish, .prerelease, .assets[].name'
```

The fork workflow should publish five benchmark APKs: arm64-v8a, armeabi-v7a, universal, x86, and x86_64.

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
