[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [string]$MappingPath,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedVersionName,

    [Parameter(Mandatory = $true)]
    [long]$ExpectedVersionCode,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedCertSha256,

    [string]$SourceJniLibsPath,
    [string]$JavaBridgePath,
    [string]$AndroidSdkRoot,
    [string]$BuildToolsVersion = "37.0.0",
    [string]$NdkVersion = "29.0.14206865",
    [string]$ChecksumPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($SourceJniLibsPath)) {
    $SourceJniLibsPath = Join-Path $repoRoot "core-rdp/src/main/jniLibs"
}
if ([string]::IsNullOrWhiteSpace($JavaBridgePath)) {
    $JavaBridgePath = Join-Path $repoRoot "core-rdp/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java"
}

function Resolve-RequiredFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Description is missing: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Resolve-RequiredDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "$Description is missing: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Resolve-Executable {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Directory,

        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    foreach ($candidateName in @($Name, "$Name.exe", "$Name.bat", "$Name.cmd")) {
        $candidate = Join-Path $Directory $candidateName
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw "Required Android tool '$Name' was not found under $Directory."
}

function Invoke-CheckedTool {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    $output = @(& $FilePath @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $textOutput = @($output | ForEach-Object { $_.ToString() })
    if ($exitCode -ne 0) {
        throw "$Description failed with exit code $exitCode.`n$($textOutput -join [Environment]::NewLine)"
    }
    return $textOutput
}

function Normalize-Sha256 {
    param([string]$Value)

    return ($Value -replace "[^0-9A-Fa-f]", "").ToLowerInvariant()
}

function Assert-ExactSet {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Actual,

        [Parameter(Mandatory = $true)]
        [string[]]$Expected,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    $difference = @(Compare-Object -ReferenceObject @($Expected | Sort-Object -Unique) `
        -DifferenceObject @($Actual | Sort-Object -Unique))
    if ($difference.Count -ne 0) {
        $rendered = $difference | ForEach-Object { "$($_.SideIndicator) $($_.InputObject)" }
        throw "$Description differs from the audited set: $($rendered -join ', ')"
    }
}

function Read-LocalAndroidSdkPath {
    $localPropertiesPath = Join-Path $repoRoot "local.properties"
    if (-not (Test-Path -LiteralPath $localPropertiesPath -PathType Leaf)) {
        return $null
    }

    $sdkLine = Get-Content -LiteralPath $localPropertiesPath | Where-Object { $_ -match "^sdk\.dir=" } |
        Select-Object -First 1
    if ($null -eq $sdkLine) {
        return $null
    }

    $sdkPath = ($sdkLine -split "=", 2)[1]
    $sdkPath = $sdkPath -replace "\\:", ":"
    $sdkPath = $sdkPath -replace "\\\\", "\"
    return $sdkPath
}

$ApkPath = Resolve-RequiredFile -Path $ApkPath -Description "Release APK"
$MappingPath = Resolve-RequiredFile -Path $MappingPath -Description "R8 mapping"
$SourceJniLibsPath = Resolve-RequiredDirectory -Path $SourceJniLibsPath -Description "Source jniLibs directory"
$JavaBridgePath = Resolve-RequiredFile -Path $JavaBridgePath -Description "LibFreeRDP Java bridge"

if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    $AndroidSdkRoot = $env:ANDROID_SDK_ROOT
}
if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    $AndroidSdkRoot = $env:ANDROID_HOME
}
if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    $AndroidSdkRoot = Read-LocalAndroidSdkPath
}
$AndroidSdkRoot = Resolve-RequiredDirectory -Path $AndroidSdkRoot -Description "Android SDK"

$buildToolsDirectory = Resolve-RequiredDirectory `
    -Path (Join-Path $AndroidSdkRoot "build-tools/$BuildToolsVersion") `
    -Description "Android Build Tools $BuildToolsVersion"
$apksigner = Resolve-Executable -Directory $buildToolsDirectory -Name "apksigner"
$zipalign = Resolve-Executable -Directory $buildToolsDirectory -Name "zipalign"
$aapt2 = Resolve-Executable -Directory $buildToolsDirectory -Name "aapt2"
$apksignerJar = Resolve-RequiredFile -Path (Join-Path $buildToolsDirectory "lib/apksigner.jar") `
    -Description "apksigner library"
$apkAnalyzerCandidate = Get-ChildItem -LiteralPath (Join-Path $AndroidSdkRoot "cmdline-tools") -Recurse -File |
    Where-Object { $_.Name -in @("apkanalyzer", "apkanalyzer.bat", "apkanalyzer.cmd") } |
    Select-Object -First 1
if ($null -eq $apkAnalyzerCandidate) {
    throw "apkanalyzer was not found under the Android SDK command-line tools."
}
$apkAnalyzer = $apkAnalyzerCandidate.FullName

$ndkPrebuiltRoot = Resolve-RequiredDirectory `
    -Path (Join-Path $AndroidSdkRoot "ndk/$NdkVersion/toolchains/llvm/prebuilt") `
    -Description "Android NDK $NdkVersion LLVM prebuilt directory"
$readelfCandidate = Get-ChildItem -LiteralPath $ndkPrebuiltRoot -Recurse -File |
    Where-Object { $_.Name -in @("llvm-readelf", "llvm-readelf.exe") } |
    Select-Object -First 1
if ($null -eq $readelfCandidate) {
    throw "llvm-readelf was not found under $ndkPrebuiltRoot."
}
$llvmReadelf = $readelfCandidate.FullName

$javaCommand = Get-Command "java" -ErrorAction SilentlyContinue
$javacCommand = Get-Command "javac" -ErrorAction SilentlyContinue
if ($null -eq $javaCommand -or $null -eq $javacCommand) {
    throw "JDK java and javac executables are required for V4 signature verification."
}

$expectedDigest = Normalize-Sha256 -Value $ExpectedCertSha256
if ($expectedDigest.Length -ne 64) {
    throw "ExpectedCertSha256 must contain exactly one SHA-256 certificate digest."
}

$supportedAbis = @("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
$requiredFreeRdpLibraries = @(
    "libc++_shared.so",
    "libcjson.so",
    "libcrypto.so",
    "libfreerdp-android.so",
    "libfreerdp-client3.so",
    "libfreerdp3.so",
    "libssl.so",
    "liburiparser.so",
    "libwinpr3.so"
)

$sourceAbiDirectories = @(Get-ChildItem -LiteralPath $SourceJniLibsPath -Directory |
    Select-Object -ExpandProperty Name)
Assert-ExactSet -Actual $sourceAbiDirectories -Expected $supportedAbis -Description "Source ABI inventory"
foreach ($abi in $supportedAbis) {
    $sourceLibraries = @(Get-ChildItem -LiteralPath (Join-Path $SourceJniLibsPath $abi) -File -Filter "*.so" |
        Select-Object -ExpandProperty Name)
    Assert-ExactSet -Actual $sourceLibraries -Expected $requiredFreeRdpLibraries `
        -Description "Source library inventory for $abi"
}

$signatureOutput = Invoke-CheckedTool -FilePath $apksigner -Arguments @(
    "verify", "--verbose", "--print-certs", "--min-sdk-version", "31", "--max-sdk-version", "37", $ApkPath
) -Description "APK signature verification for supported Android versions"
$signatureText = $signatureOutput -join "`n"
if ($signatureText -notmatch "(?m)^Verifies$") {
    throw "apksigner did not report a valid APK signature."
}
if ($signatureText -notmatch "(?m)^Number of signers: 1$") {
    throw "The release APK must have exactly one signer."
}
$certificateMatch = [regex]::Match(
    $signatureText,
    "(?im)certificate SHA-256 digest:\s*([0-9a-f:]+)"
)
if (-not $certificateMatch.Success) {
    throw "apksigner did not report the APK signing certificate SHA-256 digest."
}
$actualDigest = Normalize-Sha256 -Value $certificateMatch.Groups[1].Value
if ($actualDigest -ne $expectedDigest) {
    throw "APK signer certificate mismatch. Expected $expectedDigest; found $actualDigest."
}

$schemeChecks = @(
    @{ Api = "18"; Name = "v1"; Pattern = "Verified using v1 scheme \(JAR signing\): true" },
    @{ Api = "24"; Name = "v2"; Pattern = "Verified using v2 scheme \(APK Signature Scheme v2\): true" },
    @{ Api = "28"; Name = "v3"; Pattern = "Verified using v3 scheme \(APK Signature Scheme v3\): true" }
)
foreach ($schemeCheck in $schemeChecks) {
    $schemeOutput = Invoke-CheckedTool -FilePath $apksigner -Arguments @(
        "verify", "--verbose", "--min-sdk-version", $schemeCheck.Api,
        "--max-sdk-version", $schemeCheck.Api, $ApkPath
    ) -Description "APK $($schemeCheck.Name) signature verification"
    if (($schemeOutput -join "`n") -notmatch $schemeCheck.Pattern) {
        throw "The release APK does not contain a valid $($schemeCheck.Name) signature."
    }
}

$zipalignOutput = Invoke-CheckedTool -FilePath $zipalign -Arguments @(
    "-c", "-P", "16", "-v", "4", $ApkPath
) -Description "16 KiB APK ZIP alignment verification"
if (($zipalignOutput -join "`n") -notmatch "Verification successful") {
    throw "zipalign did not report successful verification."
}

$badgingOutput = Invoke-CheckedTool -FilePath $aapt2 -Arguments @(
    "dump", "badging", $ApkPath
) -Description "APK manifest inspection"
$badgingText = $badgingOutput -join "`n"
$packageMatch = [regex]::Match(
    $badgingText,
    "(?m)^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'"
)
if (-not $packageMatch.Success) {
    throw "aapt2 did not report the APK package and version metadata."
}
if ($packageMatch.Groups[1].Value -ne "com.hanfengruyue.pocketrdp") {
    throw "Unexpected APK application ID: $($packageMatch.Groups[1].Value)"
}
if ($packageMatch.Groups[2].Value -ne $ExpectedVersionCode.ToString()) {
    throw "APK versionCode mismatch. Expected $ExpectedVersionCode; found $($packageMatch.Groups[2].Value)."
}
if ($packageMatch.Groups[3].Value -ne $ExpectedVersionName) {
    throw "APK versionName mismatch. Expected $ExpectedVersionName; found $($packageMatch.Groups[3].Value)."
}

$mappingText = [System.IO.File]::ReadAllText($MappingPath)
$mappingClassPattern = "(?ms)^com\.freerdp\.freerdpcore\.services\.LibFreeRDP -> " +
    "com\.freerdp\.freerdpcore\.services\.LibFreeRDP:\r?\n" +
    "(?<body>.*?)(?=^[^\s#].* -> .*:$|\z)"
$mappingClassMatch = [regex]::Match($mappingText, $mappingClassPattern)
if (-not $mappingClassMatch.Success) {
    throw "R8 mapping does not retain the LibFreeRDP fully-qualified class name."
}
$mappingClassBody = $mappingClassMatch.Groups["body"].Value
$javaBridgeText = [System.IO.File]::ReadAllText($JavaBridgePath)
$requiredJniNames = @(
    [regex]::Matches(
        $javaBridgeText,
        "\bnative\s+[A-Za-z0-9_<>\[\].?]+\s+(?<name>[A-Za-z0-9_]+)\s*\("
    ) | ForEach-Object { $_.Groups["name"].Value }
)
$requiredCallbackNames = @(
    [regex]::Matches(
        $javaBridgeText,
        "\bpublic\s+static\s+[A-Za-z0-9_<>\[\].?]+\s+(?<name>On[A-Za-z0-9_]+)\s*\("
    ) | ForEach-Object { $_.Groups["name"].Value }
)
$requiredRetainedNames = @($requiredJniNames + $requiredCallbackNames | Sort-Object -Unique)
if ($requiredJniNames.Count -eq 0 -or $requiredCallbackNames.Count -eq 0) {
    throw "Could not derive JNI method and callback names from LibFreeRDP.java."
}
foreach ($methodName in $requiredCallbackNames) {
    $escapedMethodName = [regex]::Escape($methodName)
    if ($mappingClassBody -notmatch "(?m)^\s+.*\b$escapedMethodName\([^)]*\).*\s->\s$escapedMethodName$") {
        throw "R8 mapping does not retain LibFreeRDP method '$methodName'."
    }
}

$dexPackages = Invoke-CheckedTool -FilePath $apkAnalyzer -Arguments @(
    "dex", "packages", "--defined-only", $ApkPath
) -Description "APK DEX package inspection"
$dexPackageText = $dexPackages -join "`n"
if ($dexPackageText -notmatch "(?m)^C .*\tcom\.freerdp\.freerdpcore\.services\.LibFreeRDP$") {
    throw "The APK DEX does not retain the LibFreeRDP fully-qualified class name."
}
foreach ($methodName in $requiredRetainedNames) {
    $escapedMethodName = [regex]::Escape($methodName)
    $methodPattern = "(?m)^M .*\tcom\.freerdp\.freerdpcore\.services\.LibFreeRDP .*\b" +
        "$escapedMethodName\("
    if ($dexPackageText -notmatch $methodPattern) {
        throw "The APK DEX does not retain LibFreeRDP method '$methodName'."
    }
}

$idsigPath = Resolve-RequiredFile -Path "$ApkPath.idsig" -Description "APK V4 signature"
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("pocketrdp-apk-verify-" + [guid]::NewGuid().ToString("N"))
[void](New-Item -ItemType Directory -Path $tempRoot)
try {
    $v4VerifierSource = Join-Path $tempRoot "VerifyV4Signature.java"
    $v4VerifierClass = Join-Path $tempRoot "VerifyV4Signature.class"
    $v4VerifierCode = @"
import com.android.apksig.ApkVerifier;
import java.io.File;

public final class VerifyV4Signature {
    public static void main(String[] args) throws Exception {
        ApkVerifier.Result result = new ApkVerifier.Builder(new File(args[0]))
                .setMinCheckedPlatformVersion(31)
                .setMaxCheckedPlatformVersion(37)
                .setV4SignatureFile(new File(args[1]))
                .build()
                .verify();
        if (!result.isVerified() || !result.isVerifiedUsingV4Scheme()) {
            for (ApkVerifier.IssueWithParams error : result.getAllErrors()) {
                System.err.println(error);
            }
            throw new IllegalStateException("APK V4 signature verification failed");
        }
        System.out.println("V4 signature verifies");
    }
}
"@
    [System.IO.File]::WriteAllText(
        $v4VerifierSource,
        $v4VerifierCode,
        [System.Text.UTF8Encoding]::new($false)
    )
    [void](Invoke-CheckedTool -FilePath $javacCommand.Source -Arguments @(
        "-cp", $apksignerJar, "-d", $tempRoot, $v4VerifierSource
    ) -Description "V4 verifier compilation")
    if (-not (Test-Path -LiteralPath $v4VerifierClass -PathType Leaf)) {
        throw "The V4 verifier Java class was not compiled."
    }
    $v4VerifierOutput = Invoke-CheckedTool -FilePath $javaCommand.Source -Arguments @(
        "-cp", "$tempRoot$([System.IO.Path]::PathSeparator)$apksignerJar",
        "VerifyV4Signature", $ApkPath, $idsigPath
    ) -Description "APK V4 signature verification"
    if (($v4VerifierOutput -join "`n") -notmatch "(?m)^V4 signature verifies$") {
        throw "The Android apksig library did not confirm the APK V4 signature."
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $extractRoot = Join-Path $tempRoot "apk"
    [void](New-Item -ItemType Directory -Path $extractRoot)
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        $libraryEntries = @($zip.Entries | Where-Object {
            $_.FullName -match "^lib/([^/]+)/([^/]+\.so)$"
        })
        if ($libraryEntries.Count -eq 0) {
            throw "The release APK does not contain native libraries."
        }

        $apkAbis = @($libraryEntries | ForEach-Object {
            ([regex]::Match($_.FullName, "^lib/([^/]+)/")).Groups[1].Value
        } | Sort-Object -Unique)
        Assert-ExactSet -Actual $apkAbis -Expected $supportedAbis -Description "APK ABI inventory"

        $extractedLibraries = @{}
        foreach ($abi in $supportedAbis) {
            $abiEntries = @($libraryEntries | Where-Object { $_.FullName -like "lib/$abi/*" })
            $apkLibraryNames = @($abiEntries | ForEach-Object { Split-Path $_.FullName -Leaf })
            foreach ($requiredLibrary in $requiredFreeRdpLibraries) {
                if ($apkLibraryNames -notcontains $requiredLibrary) {
                    throw "APK ABI $abi is missing required library $requiredLibrary."
                }
            }
            if (@($apkLibraryNames | Sort-Object -Unique).Count -ne $apkLibraryNames.Count) {
                throw "APK ABI $abi contains duplicate native-library names."
            }

            $abiExtractRoot = Join-Path $extractRoot $abi
            [void](New-Item -ItemType Directory -Path $abiExtractRoot)
            $extractedLibraries[$abi] = @{}
            foreach ($entry in $abiEntries) {
                $libraryName = Split-Path $entry.FullName -Leaf
                $destination = Join-Path $abiExtractRoot $libraryName
                $inputStream = $entry.Open()
                $outputStream = [System.IO.File]::Create($destination)
                try {
                    $inputStream.CopyTo($outputStream)
                }
                finally {
                    $outputStream.Dispose()
                    $inputStream.Dispose()
                }
                $extractedLibraries[$abi][$libraryName] = $destination
            }
        }
    }
    finally {
        $zip.Dispose()
    }

    $androidSystemLibraries = @(
        "libaaudio.so", "libamidi.so", "libandroid.so", "libbinder_ndk.so", "libc.so",
        "libcamera2ndk.so", "libdl.so", "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so",
        "libGLESv3.so", "libjnigraphics.so", "liblog.so", "libm.so", "libmediandk.so",
        "libnativewindow.so", "libOpenMAXAL.so", "libOpenSLES.so", "libstdc++.so",
        "libsync.so", "libvulkan.so", "libz.so"
    )

    foreach ($abi in $supportedAbis) {
        $packagedNames = @($extractedLibraries[$abi].Keys)
        foreach ($libraryName in $packagedNames) {
            $libraryPath = $extractedLibraries[$abi][$libraryName]
            $programHeaders = Invoke-CheckedTool -FilePath $llvmReadelf -Arguments @(
                "-lW", $libraryPath
            ) -Description "ELF program-header inspection for $abi/$libraryName"
            $programHeaderText = $programHeaders -join "`n"
            if ($programHeaderText -notmatch "(?m)^\s*GNU_RELRO\s") {
                throw "ELF $abi/$libraryName does not contain GNU_RELRO."
            }
            $gnuStackLine = $programHeaders | Where-Object { $_ -match "^\s*GNU_STACK\s" } |
                Select-Object -First 1
            if ($null -eq $gnuStackLine) {
                throw "ELF $abi/$libraryName does not declare GNU_STACK."
            }
            if ($gnuStackLine -match "\sE\s+0x[0-9A-Fa-f]+\s*$") {
                throw "ELF $abi/$libraryName requests an executable stack."
            }

            if ($abi -in @("arm64-v8a", "x86_64")) {
                $loadLines = @($programHeaders | Where-Object { $_ -match "^\s*LOAD\s" })
                if ($loadLines.Count -eq 0) {
                    throw "ELF $abi/$libraryName does not contain LOAD segments."
                }
                foreach ($loadLine in $loadLines) {
                    $alignmentMatch = [regex]::Match($loadLine, "0x([0-9A-Fa-f]+)\s*$")
                    if (-not $alignmentMatch.Success) {
                        throw "Could not parse LOAD alignment for ELF $abi/$libraryName."
                    }
                    $alignment = [Convert]::ToInt64($alignmentMatch.Groups[1].Value, 16)
                    if ($alignment -lt 0x4000) {
                        throw "ELF $abi/$libraryName has LOAD alignment below 16 KiB: $loadLine"
                    }
                }
            }

            $sectionHeaders = Invoke-CheckedTool -FilePath $llvmReadelf -Arguments @(
                "-SW", $libraryPath
            ) -Description "ELF section inspection for $abi/$libraryName"
            if (($sectionHeaders -join "`n") -match "(?m)\s\.debug(?:_|\s)") {
                throw "Packaged ELF $abi/$libraryName still contains debug sections."
            }

            $dynamicSection = Invoke-CheckedTool -FilePath $llvmReadelf -Arguments @(
                "-dW", $libraryPath
            ) -Description "ELF dependency inspection for $abi/$libraryName"
            $neededLibraries = @(
                [regex]::Matches(
                    ($dynamicSection -join "`n"),
                    "Shared library: \[([^\]]+)\]"
                ) | ForEach-Object { $_.Groups[1].Value }
            )
            foreach ($neededLibrary in $neededLibraries) {
                if ($packagedNames -notcontains $neededLibrary -and
                    $androidSystemLibraries -notcontains $neededLibrary) {
                    throw "ELF dependency closure failed for $abi/${libraryName}: $neededLibrary is not packaged or allowlisted."
                }
            }
        }
    }
}
finally {
    $resolvedTempRoot = [System.IO.Path]::GetFullPath($tempRoot)
    $expectedTempPrefix = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if ($resolvedTempRoot.StartsWith($expectedTempPrefix, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path $resolvedTempRoot -Leaf).StartsWith("pocketrdp-apk-verify-")) {
        Remove-Item -LiteralPath $resolvedTempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

$apkHash = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ([string]::IsNullOrWhiteSpace($ChecksumPath)) {
    $ChecksumPath = "$ApkPath.sha256"
}
$checksumDirectory = Split-Path -Parent $ChecksumPath
if (-not [string]::IsNullOrWhiteSpace($checksumDirectory) -and
    -not (Test-Path -LiteralPath $checksumDirectory -PathType Container)) {
    [void](New-Item -ItemType Directory -Path $checksumDirectory)
}
$checksumLine = "$apkHash  $(Split-Path $ApkPath -Leaf)`n"
[System.IO.File]::WriteAllText($ChecksumPath, $checksumLine, [System.Text.Encoding]::ASCII)

Write-Host "APK_SHA256=$apkHash"
Write-Host "APK_CERT_SHA256=$actualDigest"
Write-Host "APK_SIGNATURE_SCHEMES=V1,V2,V3,V4"
Write-Host "APK_ABIS=$($supportedAbis -join ',')"
Write-Host "APK_ELF_DEPENDENCY_CLOSURE=PASS"
Write-Host "APK_64BIT_ELF_16K_ALIGNMENT=PASS"
Write-Host "APK_R8_JNI_RETENTION=PASS"
Write-Host "APK_VERSION=$ExpectedVersionName ($ExpectedVersionCode)"
Write-Host "APK_CHECKSUM_FILE=$ChecksumPath"
