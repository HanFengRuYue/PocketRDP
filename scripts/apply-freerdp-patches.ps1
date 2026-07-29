[CmdletBinding()]
param(
    [switch]$CheckOnly
)

$ErrorActionPreference = "Stop"
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
# Windows PowerShell 5 inherits a non-UTF-8 native-process code page when launched through WSL.
# Without this, `git diff` mojibakes non-ASCII patch comments and the exact-diff safety check
# falsely reports the already-applied audited patch as unrelated changes.
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom

$repoRoot = Split-Path -Parent $PSScriptRoot
$freeRdpDir = Join-Path $repoRoot "third_party/FreeRDP"
$patchPath = Join-Path $repoRoot "patches/freerdp/pocketrdp-3.30.patch"
$expectedBase = "6b107f0aadbabc47941c5a5b893b88c01792af6d"
$expectedIntegratedCommit = "07990e027ceb28370e9a7215a3f847e5b5b3018f"
$expectedPatchSha256 = "86a7c89f1cbd52050e7cc7c64895d4b538994b3ae857404e23ad18d753a1d95f"

if (-not (Test-Path -LiteralPath (Join-Path $freeRdpDir ".git"))) {
    throw "FreeRDP submodule is not initialized. Run: git submodule update --init third_party/FreeRDP"
}
if (-not (Test-Path -LiteralPath $patchPath -PathType Leaf)) {
    throw "PocketRDP FreeRDP patch is missing: $patchPath"
}
$actualPatchSha256 = (Get-FileHash -LiteralPath $patchPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualPatchSha256 -ne $expectedPatchSha256) {
    throw "PocketRDP FreeRDP patch SHA-256 mismatch. Expected $expectedPatchSha256; found $actualPatchSha256."
}

$head = (& git -C $freeRdpDir rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Could not read the FreeRDP submodule HEAD."
}
$status = @(& git -C $freeRdpDir status --porcelain --untracked-files=all)
if ($LASTEXITCODE -ne 0) {
    throw "Could not inspect the FreeRDP submodule worktree."
}
if ($head -eq $expectedIntegratedCommit) {
    if ($status.Count -gt 0) {
        throw "The integrated PocketRDP FreeRDP commit has uncommitted changes."
    }
    Write-Host "PocketRDP FreeRDP custom commit is checked out cleanly."
    exit 0
}
if ($head -ne $expectedBase) {
    throw "FreeRDP must be at PocketRDP commit $expectedIntegratedCommit or official 3.30.0 base $expectedBase; found $head."
}

if ($status.Count -gt 0) {
    # A reverse-applicable patch is not sufficient proof: the worktree could contain the expected
    # patch plus an unrelated tracked or untracked change. Require the complete HEAD-relative diff
    # to equal the audited patch byte-for-byte after newline normalization. New files are folded
    # into that canonical diff explicitly because `git diff HEAD` does not include untracked files.
    $currentDiffLines = @(& git -C $freeRdpDir diff --binary HEAD --)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect the FreeRDP submodule diff."
    }
    $currentDiff = ($currentDiffLines -join "`n").TrimEnd("`n")
    $expectedDiff = ([System.IO.File]::ReadAllText(
        $patchPath,
        $utf8NoBom
    )).Replace("`r`n", "`n").TrimEnd("`n")
    $untrackedFiles = @($status | Where-Object { $_.StartsWith("??") } | ForEach-Object {
        $_.Substring(3)
    } | Sort-Object)
    foreach ($relativePath in $untrackedFiles) {
        $newFileDiff = @(& git -c core.autocrlf=false -c core.safecrlf=false -C $freeRdpDir `
            diff --no-index --binary -- /dev/null $relativePath 2>$null)
        if ($LASTEXITCODE -notin 0, 1) {
            throw "Could not inspect new FreeRDP file: $relativePath"
        }
        if ($newFileDiff.Count -gt 0) {
            $currentDiffLines += $newFileDiff
        }
    }
    $currentDiff = ($currentDiffLines -join "`n").TrimEnd("`n")
    if ($currentDiff -ceq $expectedDiff) {
        Write-Host "PocketRDP FreeRDP 3.30 patch is already applied."
        exit 0
    }
    throw "FreeRDP has unrelated or partially applied changes; refusing to modify it."
}

& git -C $freeRdpDir apply --check --whitespace=error-all $patchPath
if ($LASTEXITCODE -ne 0) {
    throw "PocketRDP FreeRDP patch does not apply cleanly to the official 3.30.0 base."
}
if ($CheckOnly) {
    Write-Host "PocketRDP FreeRDP 3.30 patch applies cleanly."
    exit 0
}

& git -C $freeRdpDir apply --whitespace=error-all $patchPath
if ($LASTEXITCODE -ne 0) {
    throw "PocketRDP FreeRDP patch application failed."
}
Write-Host "Applied PocketRDP FreeRDP 3.30 patch."
