param(
    [string]$VersionName
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $repoRoot

if ([string]::IsNullOrWhiteSpace($VersionName)) {
    $propsPath = Join-Path $repoRoot "gradle.properties"
    if (Test-Path $propsPath) {
        $line = Get-Content -Encoding utf8 $propsPath | Where-Object { $_ -match "^\s*VERSION_NAME\s*=" } | Select-Object -First 1
        if ($line) {
            $VersionName = ($line -split "=", 2)[1].Trim()
        }
    }
}

if ([string]::IsNullOrWhiteSpace($VersionName)) {
    $VersionName = "0.1.0"
}

$gitCount = $null
$gitShort = $null
try { $gitCount = (& git rev-list --count HEAD).Trim() } catch { }
try { $gitShort = (& git rev-parse --short=7 HEAD).Trim() } catch { }

$buildNumber = $null
if (-not [string]::IsNullOrWhiteSpace($env:BUILD_NUMBER)) {
    $buildNumber = $env:BUILD_NUMBER
} elseif (-not [string]::IsNullOrWhiteSpace($gitCount)) {
    $buildNumber = $gitCount
} else {
    $buildNumber = "1"
}

Write-Host "Base version: $VersionName"
Write-Host "Build number: $buildNumber"
if ($gitShort) {
    Write-Host "Git hash: $gitShort"
}

& "$repoRoot\gradlew.bat" :app:assembleRelease -PVERSION_NAME="$VersionName" -PVERSION_CODE="$buildNumber"

$releaseDir = Join-Path $repoRoot "app\build\outputs\apk\release"
if (Test-Path $releaseDir) {
    Get-ChildItem -Path $releaseDir -Filter *.apk | ForEach-Object {
        $name = $_.Name
        $abi = $null
        if ($name -match "^app-(.+)-release\.apk$") {
            $abi = $Matches[1]
        } elseif ($name -eq "app-release.apk") {
            $abi = "universal"
        }

        if ([string]::IsNullOrWhiteSpace($abi)) {
            return
        }

        $targetName = "TrafficSIM-v$VersionName-$abi-release.apk"
        $targetPath = Join-Path $releaseDir $targetName
        if (Test-Path $targetPath) {
            Remove-Item -Force $targetPath
        }
        Move-Item -Force $_.FullName $targetPath
    }
}
