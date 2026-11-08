# Copies the prebuilt libusb DLL for the host architecture, published by the
# libusb project with each release, to dist\native\windows-<arch>.
#
# Usage: native\build-windows.ps1 -Arch x64|ARM64
param(
    [Parameter(Mandatory = $true)][ValidateSet('x64', 'ARM64')][string]$Arch
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

$Root = Split-Path -Parent $PSScriptRoot
$Versions = @{}
Get-Content "$Root\native\versions.env" | Where-Object { $_ -match '^[A-Z0-9_]+=' } | ForEach-Object {
    $Key, $Value = $_ -split '=', 2
    $Versions[$Key] = $Value
}
$LibusbVersion = $Versions.LIBUSB_VERSION

if ($Arch -eq 'x64') {
    $Platform = 'windows-x86_64'
    $UsbDir = 'VS2022\MS64\dll'
} else {
    $Platform = 'windows-aarch64'
    $UsbDir = 'VS2025\ARM64\dll'
}
$Out = "$Root\dist\native\$Platform"
$Licenses = "$Root\dist\native\licenses"
$Work = "$Root\build\native-work"
Remove-Item -Recurse -Force $Work, $Out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Work, $Out, $Licenses | Out-Null
Set-Location $Work

Write-Host "=== libusb $LibusbVersion ==="
$Archive = "libusb-$LibusbVersion.7z"
Invoke-WebRequest "https://github.com/libusb/libusb/releases/download/v$LibusbVersion/$Archive" -OutFile $Archive
$Hash = (Get-FileHash $Archive -Algorithm SHA256).Hash
if ($Hash -ine $Versions.LIBUSB_WINDOWS_7Z_SHA256) {
    throw "libusb archive checksum mismatch: $Hash"
}
7z x $Archive -ousb | Out-Null
$UsbHeader = Get-ChildItem -Recurse -Path usb\include -Filter libusb.h | Select-Object -First 1
if (-not $UsbHeader) { throw 'libusb.h not found in the libusb archive' }

Write-Host "=== packaging $Platform ==="
Copy-Item "$Work\usb\$UsbDir\libusb-1.0.dll" "$Out\libusb-1.0.dll"
# The license text is identical in every archive; COPYING is at its root.
$Copying = Get-ChildItem -Recurse -Path usb -Filter COPYING | Select-Object -First 1
if ($Copying) { Copy-Item $Copying.FullName "$Licenses\libusb-COPYING" }
@(
    "libusb.version=$LibusbVersion",
    "libusb.source=$Archive $UsbDir"
) | Set-Content -Encoding ascii "$Out\build-info.properties"
Get-ChildItem $Out
