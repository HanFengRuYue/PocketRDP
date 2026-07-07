param(
    [string]$SourcePath = "third_party/FreeRDP/channels/rdpdr/client/rdpdr_main.c"
)

$ErrorActionPreference = "Stop"

$source = Get-Content -Raw -LiteralPath $SourcePath
$tryAdvance = [regex]::Match(
    $source,
    "static BOOL tryAdvance\s*\([^)]*\)\s*\{(?<body>[\s\S]*?)\n\}",
    [System.Text.RegularExpressions.RegexOptions]::Singleline
)

if (-not $tryAdvance.Success) {
    throw "tryAdvance() was not found in $SourcePath"
}

$body = $tryAdvance.Groups["body"].Value

if ($body -notmatch "rdpdr_send_device_list_announce_request\s*\(\s*rdpdr\s*,\s*FALSE\s*\)") {
    throw "Initial ready announce must not mark userLoggedOn. Sending filesystem devices before USER_LOGGEDON can make Windows close the channel/session."
}

if ($body -match "rdpdr_send_device_list_announce_request\s*\(\s*rdpdr\s*,\s*TRUE\s*\)") {
    throw "tryAdvance() must not force filesystem drive announce before PAKID_CORE_USER_LOGGEDON."
}

if ($source -notmatch "PAKID_CORE_USER_LOGGEDON[\s\S]*?rdpdr_send_device_list_announce_request\s*\(\s*rdpdr\s*,\s*TRUE\s*\)") {
    throw "USER_LOGGEDON handling must announce filesystem devices after login."
}

Write-Host "RDPDR drive announce timing check passed."
