<#
.SYNOPSIS
  Installs the pom.xml version pre-push hook into the current Git repository.

.PARAMETER Version
  Release tag to install. Default: the latest GitHub release.

.PARAMETER Branches
  Branch globs to protect. Only used if .githooks\release-branches does not exist yet.

.PARAMETER Source
  Local folder (containing .githooks\) or .zip to install from instead of GitHub.

.PARAMETER Force
  Overwrite a foreign .githooks\pre-push and replace a different core.hooksPath.

.PARAMETER Uninstall
  Remove the hook files and unset core.hooksPath.
#>
[CmdletBinding()]
param(
    [string]$Version = 'latest',
    [string[]]$Branches = @('master', 'release/*'),
    [string]$Source,
    [switch]$Force,
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'
$Repo  = 'abordeianu02/Yet-another-CI-Project'
$Files = @('pre-push', 'VersionCheck.java', 'bump-version.cmd', 'setup-hooks.cmd')
$Branches = @($Branches | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })

function Write-Text([string]$Path, [string]$Text, [switch]$Lf) {
    if ($Lf) { $Text = $Text -replace "`r`n", "`n" }
    [IO.File]::WriteAllText($Path, $Text, (New-Object Text.UTF8Encoding($false)))
}

# --- locate the repository ---------------------------------------------------
$root = (& git rev-parse --show-toplevel 2>$null)
if ($LASTEXITCODE -ne 0 -or -not $root) { throw 'Not inside a Git repository. cd into your project first.' }
Set-Location $root
$hooks = Join-Path $root '.githooks'

# --- uninstall ---------------------------------------------------------------
if ($Uninstall) {
    foreach ($f in $Files) { Remove-Item (Join-Path $hooks $f) -ErrorAction SilentlyContinue }
    if ((& git config core.hooksPath) -eq '.githooks') { & git config --unset core.hooksPath }
    Write-Host 'Removed hook files and unset core.hooksPath.'
    Write-Host '(.githooks\release-branches and the .gitattributes rules were left in place.)'
    return
}

# --- pre-flight checks -------------------------------------------------------
if (-not (Test-Path (Join-Path $root 'pom.xml'))) {
    throw "No pom.xml at the repository root ($root). The hook only supports a root pom.xml."
}
$currentPath = (& git config core.hooksPath)
if ($currentPath -and $currentPath -ne '.githooks' -and -not $Force) {
    throw "core.hooksPath is already '$currentPath'. Re-run with -Force to replace it."
}
$existing = Join-Path $hooks 'pre-push'
if ((Test-Path $existing) -and -not $Force -and -not (Select-String -Path $existing -Pattern 'VersionCheck.java' -Quiet)) {
    throw '.githooks\pre-push exists and is not this hook. Re-run with -Force to overwrite it.'
}

# --- fetch the package -------------------------------------------------------
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('pomhook-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    $label = 'local source'
    if ($Source -and (Test-Path $Source -PathType Container)) {
        $srcHooks = Join-Path $Source '.githooks'
    }
    else {
        if ($Source) {
            $zip = $Source
        }
        else {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            $label = $Version
            if ($Version -eq 'latest') {
                try {
                    $label = (Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest" -UseBasicParsing).tag_name
                }
                catch {
                    throw "Could not find a GitHub release for $Repo. Create one first, or pass -Version <tag>."
                }
            }
            $zip = Join-Path $tmp 'src.zip'
            Invoke-WebRequest "https://github.com/$Repo/archive/refs/tags/$label.zip" -OutFile $zip -UseBasicParsing
        }
        $out = Join-Path $tmp 'x'
        Expand-Archive -Path $zip -DestinationPath $out
        $srcHooks = Join-Path (Get-ChildItem $out -Directory | Select-Object -First 1).FullName '.githooks'
    }
    if (-not (Test-Path (Join-Path $srcHooks 'pre-push'))) {
        throw 'The package contains no .githooks\pre-push. Wrong tag?'
    }

    # --- install files -------------------------------------------------------
    New-Item -ItemType Directory -Force -Path $hooks | Out-Null
    foreach ($f in $Files) { Copy-Item (Join-Path $srcHooks $f) (Join-Path $hooks $f) -Force }

    # The shell hook must have LF endings, whatever the package was built with.
    Write-Text $existing (Get-Content $existing -Raw) -Lf

    $rb = Join-Path $hooks 'release-branches'
    if (-not (Test-Path $rb)) {
        Write-Text $rb ("# One branch glob per line. Pushes to matching branches require a version bump.`n" +
                        ($Branches -join "`n") + "`n") -Lf
    }
}
finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

# --- .gitattributes: keep line endings right on Windows ----------------------
$ga    = Join-Path $root '.gitattributes'
$rules = @('.githooks/pre-push text eol=lf', '.githooks/release-branches text eol=lf', '.githooks/*.cmd text eol=crlf')
$gaText = ''
if (Test-Path $ga) { $gaText = [IO.File]::ReadAllText($ga) }
$missing = @($rules | Where-Object { $gaText -notmatch [regex]::Escape($_) })
if ($missing.Count -gt 0) {
    $prefix = ''
    if ($gaText -and -not $gaText.EndsWith("`n")) { $prefix = "`n" }
    [IO.File]::AppendAllText($ga, $prefix + ($missing -join "`n") + "`n", (New-Object Text.UTF8Encoding($false)))
}

# --- git setup ---------------------------------------------------------------
& git add -- .githooks .gitattributes
& git update-index --chmod=+x .githooks/pre-push
& git config core.hooksPath .githooks
& git config push.followTags true

# --- done --------------------------------------------------------------------
Write-Host ''
Write-Host "Installed pom-version hook ($label) into $root"
Write-Host '  1. Review "git status", then commit the staged files.'
Write-Host '  2. Protected branches: see .githooks\release-branches'
Write-Host '  3. Teammates run .githooks\setup-hooks.cmd once after cloning.'
if (-not (Get-Command java -ErrorAction SilentlyContinue) -and -not $env:JAVA_HOME) {
    Write-Warning 'No java on PATH and JAVA_HOME is not set. The hook needs JDK 17+ to run.'
}
