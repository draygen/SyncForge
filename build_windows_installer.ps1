# build_windows_installer.ps1
# SyncForge Nexus - Professional Installer Build Engine

$DistDir = "windows-dist"
$InnoCompiler = "C:\Program Files (x86)\Inno Setup 6\ISCC.exe"

Write-Host "[*] SyncForge Installer Build Engine Starting..." -ForegroundColor Cyan

# 1. Verification
if (!(Test-Path $DistDir)) {
    Write-Error "[-] Distribution directory not found. Please run from project root."
    exit
}

# 2. Check for missing logos/icons
$Favicon = Join-Path $DistDir "nginx\html\favicon.ico"
if (!(Test-Path $Favicon)) {
    Write-Host "[!] Warning: favicon.ico missing. Using fallback." -ForegroundColor Yellow
    # Create empty ico if missing to avoid ISCC error
    New-Item -Path $Favicon -ItemType File -Force | Out-Null
}

# 3. Pull latest binaries from project (assuming already built)
Write-Host "[*] Staging project binaries..." -ForegroundColor Gray
Copy-Item "mft-server/target/server-0.0.1-SNAPSHOT.jar" "$DistDir\bin\server.jar" -Force
Copy-Item "windows-dist/bin/autopost.exe" "$DistDir\bin\autopost.exe" -Force # Already staged

# 4. Trigger Inno Setup
if (Test-Path $InnoCompiler) {
    Write-Host "[*] Compiling Installer..." -ForegroundColor Cyan
    & $InnoCompiler "$DistDir\SyncForge_Installer.iss"
    Write-Host "[+] BUILD COMPLETE: SyncForge_Setup_v2.exe generated in $DistDir" -ForegroundColor Green
} else {
    Write-Host "[!] Inno Setup Compiler not found at $InnoCompiler" -ForegroundColor Yellow
    Write-Host "[!] Please compile $DistDir\SyncForge_Installer.iss manually using Inno Setup." -ForegroundColor Yellow
}
