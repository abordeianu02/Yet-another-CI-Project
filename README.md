# Version Guard

A small Git pre-push hook for Maven projects. Before you push to a release branch such as `master`, it checks that the version in `pom.xml` has gone **up**, so the same version is never published twice.

```
pre-push: push to 'master' blocked.
  pom.xml version is 1.2.3 but must be greater than 1.2.3 on origin/master (a1b2c3d).
  Fix: .githooks\bump-version.cmd   then push again.
```

A helper script, `bump-version`, asks you for the new version, commits it and tags it. You always choose the number yourself.

## Requirements

- Windows with Git for Windows
- JDK 17 or newer, on `PATH` or in `JAVA_HOME`
- A Maven project with `pom.xml` at the repository root

## Install

Open PowerShell in the root of your project and run:

```powershell
iwr https://raw.githubusercontent.com/abordeianu02/Yet-another-CI-Project/master/install.ps1 -OutFile $env:TEMP\install-versionguard.ps1
powershell -ExecutionPolicy Bypass -File $env:TEMP\install-versionguard.ps1
```

The installer copies the hook into `.githooks/`, sets up Git, and stages the files. Review them with `git status`, then commit and push.

Options (append to the second command):

| Option | Effect |
|---|---|
| `-Version 1.2.0` | Install a specific release instead of the latest |
| `-Branches 'master,release/*'` | Branches to protect (only used on first install) |
| `-Force` | Overwrite an existing, different `pre-push` hook or `core.hooksPath` |
| `-Uninstall` | Remove the hook and unset `core.hooksPath` |

**Teammates:** after cloning, run `.githooks\setup-hooks.cmd` once. Git does not copy hook settings between clones.

## Everyday use

1. Work and push to feature branches as usual. They are not checked.
2. Ready to release? On `master`, run `.githooks\bump-version.cmd`. Pick patch, minor or major, or type your own version.
3. Run `git push`. The version commit and its tag go up together.

**Test builds:** type a version with a counter suffix, like `1.2.4-alpha.1`, then `1.2.4-alpha.2`. A repeated suffix is blocked, and an alpha pushed to a published branch is a real release that cannot be replaced.

**Version rules:** the version must be strictly greater than the one on the remote branch. The format is `MAJOR.MINOR.PATCH`, optionally followed by `-suffix`. Order: `1.2.3-alpha.1` < `1.2.3-alpha.2` < `1.2.3` < `1.2.4-alpha.1`.

**Which branches:** edit `.githooks/release-branches`, one pattern per line (`master`, `release/*`). The list is per project.

## Danger zone

These skip the safety net. Use them only when you mean to.

- **Skip once:** `git push --no-verify`
- **Turn off in this clone:** `git config --unset core.hooksPath` (turn on again with `.githooks\setup-hooks.cmd`)
- **Stop protecting a branch:** remove it from `.githooks/release-branches` and commit

What you give up: nothing else checks versions on the client. If your artifact repository allows redeploys, a duplicate version will silently overwrite the published one. Merges done in a web UI (pull requests) never run the hook.

**Legitimate bypass, first push on an older project:** if the remote still has a version like `1.0-SNAPSHOT`, the hook cannot compare against it. Set a valid version in `pom.xml`, commit, and run `git push --no-verify --follow-tags` once. Normal checks apply from then on.

## Troubleshooting

- **"java not found":** set `JAVA_HOME` (user environment variable) and restart your IDE.
- **"remote tip is not in your local repository":** run `git fetch`, then push again.
- **"declares no `<version>` of its own":** the root pom inherits its version or uses `${revision}`. Not supported.

## Technical details

| File | Role |
|---|---|
| `install.ps1` | Installer. Downloads a release, copies the files below into `.githooks/`, adds `.gitattributes` rules, sets the executable bit, `core.hooksPath` and `push.followTags`. Never commits. |
| `.githooks/pre-push` | POSIX shell hook. Reads the pushed refs, matches branches against `release-branches`, finds Java, and calls `VersionCheck.java` per matching branch. Ignores deletions and tags. |
| `.githooks/VersionCheck.java` | All logic, as a single-file Java program (`java VersionCheck.java ...`). Mode `check` validates a push, mode `bump` runs the interactive bump. |
| `.githooks/release-branches` | Branch patterns to protect. Blank lines and `#` comments allowed. |
| `.githooks/bump-version.cmd` | Windows wrapper for `VersionCheck.java bump`. |
| `.githooks/setup-hooks.cmd` | Per-clone setup: `core.hooksPath` and `push.followTags`. |
| `.gitattributes` | Keeps `pre-push` on LF line endings (CRLF would break the shell script). |

**How the check works**

- The version is read from the root `pom.xml` of the pushed commit and of the remote tip, never from the working tree.
- For a new branch with no remote tip, the baseline is the nearest reachable tag. Keep tags in the `1.2.3` format.
- Anything it cannot evaluate (no Java, unparseable pom, remote tip not fetched) blocks the push with a message.
- Only the tip of the push is compared, so several commits can ride along with one bump.

**How `bump` works:** it requires `pom.xml` to be free of uncommitted changes, replaces only the project's own `<version>` (never the parent's), commits only `pom.xml` with the message `build: release x.y.z`, and creates the annotated tag `x.y.z`.

**Publishing a release of this kit:** the installer downloads the source archive of a GitHub release tag. Create the release from the `x.y.z` tag that `bump-version` made, and mark alphas as *pre-release* so they never count as "latest".
