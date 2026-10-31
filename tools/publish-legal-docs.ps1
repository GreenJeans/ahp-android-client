<#
.SYNOPSIS
    Publishes only the public legal/privacy documents to a standalone public GitHub
    repository (GreenJeans/ahp-client-legal) for GitHub Pages hosting, keeping the
    main ahp-android-client source repository private.

.EXAMPLE
    .\tools\publish-legal-docs.ps1
#>
[CmdletBinding()]
param(
    [string]$RemoteUrl = "https://github.com/GreenJeans/ahp-client-legal.git",
    [string]$Branch = "main"
)

$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$SourcePolicy = Join-Path $RepoRoot "docs\privacy-policy.md"

if (-not (Test-Path $SourcePolicy)) {
    throw "Privacy policy not found at $SourcePolicy"
}

$StagingDir = Join-Path ([System.IO.Path]::GetTempPath()) ("ahp-client-legal-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $StagingDir -Force | Out-Null

try {
    Copy-Item -Path $SourcePolicy -Destination (Join-Path $StagingDir "privacy-policy.md") -Force
    Copy-Item -Path $SourcePolicy -Destination (Join-Path $StagingDir "index.md") -Force

    $Readme = @"
# AHP Client — Legal & Privacy Documentation

This repository hosts the public Privacy Policy and legal disclosures for **AHP Client** (`dev.ahpclient`) on Google Play.

- [Privacy Policy](privacy-policy.md)
"@
    Set-Content -Path (Join-Path $StagingDir "README.md") -Value $Readme -Encoding UTF8

    Push-Location $StagingDir
    try {
        git init -b $Branch
        git add README.md index.md privacy-policy.md
        git commit -m "Publish AHP Client privacy policy"
        git remote add origin $RemoteUrl
        Write-Host "Pushing legal docs to $RemoteUrl ($Branch)..." -ForegroundColor Cyan
        git push -u origin $Branch --force
        Write-Host "Done! Enable GitHub Pages on $RemoteUrl (Branch: $Branch, Folder: /root)." -ForegroundColor Green
    } finally {
        Pop-Location
    }
} finally {
    if (Test-Path $StagingDir) {
        Remove-Item -Path $StagingDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
