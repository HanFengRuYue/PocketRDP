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

if ($body -match "rdpdr_send_device_list_announce_request\s*\(\s*rdpdr\s*,\s*FALSE\s*\)") {
    throw "Reconnect drive announce guard is still false: filesystem devices can be skipped until USER_LOGGEDON."
}

if ($body -notmatch "rdpdr_send_device_list_announce_request\s*\(\s*rdpdr\s*,\s*TRUE\s*\)") {
    throw "tryAdvance() does not force an initial device announce that includes filesystem drives."
}

if ($source -notmatch "PAKID_CORE_USER_LOGGEDON[\s\S]*?!rdpdr->userLoggedOn[\s\S]*?rdpdr_send_device_list_announce_request\s*\(\s*rdpdr\s*,\s*TRUE\s*\)") {
    throw "USER_LOGGEDON handling does not guard against duplicate filesystem device announces."
}

Write-Host "RDPDR reconnect drive announce check passed."
