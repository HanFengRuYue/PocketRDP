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
$freeRdpDir = Join-Path $repoRoot "third_party\FreeRDP"
$patchPath = Join-Path $repoRoot "patches\freerdp\pocketrdp-3.30.patch"
$expectedBase = "6b107f0aadbabc47941c5a5b893b88c01792af6d"
$expectedPatchSha256 = "c4b3d2abc2e43352b697e6618e2a3b52a8f61cfdc9fac2769294e73177a56fea"

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
if ($head -ne $expectedBase) {
    throw "FreeRDP must be at official 3.30.0 commit $expectedBase; found $head."
}

$status = @(& git -C $freeRdpDir status --porcelain --untracked-files=all)
if ($LASTEXITCODE -ne 0) {
    throw "Could not inspect the FreeRDP submodule worktree."
}
if ($status.Count -gt 0) {
    # A reverse-applicable patch is not sufficient proof: the worktree could contain the expected
    # patch plus an unrelated tracked or untracked change. Require the complete HEAD-relative diff
    # to equal the audited patch byte-for-byte after newline normalization, and reject all
    # untracked files. This also covers a patch that was staged before running the helper.
    $currentDiffLines = @(& git -C $freeRdpDir diff --binary HEAD --)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect the FreeRDP submodule diff."
    }
    $currentDiff = ($currentDiffLines -join "`n").TrimEnd("`n")
    $expectedDiff = ([System.IO.File]::ReadAllText(
        $patchPath,
        $utf8NoBom
    )).Replace("`r`n", "`n").TrimEnd("`n")
    $hasUntrackedFiles = $status | Where-Object { $_.StartsWith("??") }
    if (-not $hasUntrackedFiles -and $currentDiff -ceq $expectedDiff) {
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
