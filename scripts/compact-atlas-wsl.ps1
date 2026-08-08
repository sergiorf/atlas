[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidateNotNullOrEmpty()]
  [string]$Distro,

  [string]$VhdPath,

  [switch]$Force
)

$ErrorActionPreference = 'Stop'

function Resolve-WslVhdPath {
  param([string]$Distribution)

  $roots = Get-ChildItem 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Lxss' -ErrorAction Stop
  $entry = $roots | Where-Object { (Get-ItemProperty $_.PSPath).DistributionName -eq $Distribution }
  if (@($entry).Count -ne 1) {
    throw "Expected exactly one registered WSL distribution named '$Distribution'."
  }
  $basePath = (Get-ItemProperty $entry.PSPath).BasePath
  $candidate = Join-Path $basePath 'ext4.vhdx'
  return [System.IO.Path]::GetFullPath($candidate)
}

$registered = @(wsl.exe --list --quiet) | ForEach-Object { $_.Trim([char]0).Trim() } | Where-Object { $_ }
if ($registered -notcontains $Distro) {
  throw "WSL distribution '$Distro' is not registered. Available: $($registered -join ', ')"
}

$resolvedVhd = if ($VhdPath) { [System.IO.Path]::GetFullPath($VhdPath) } else { Resolve-WslVhdPath $Distro }
if ([System.IO.Path]::GetExtension($resolvedVhd) -ne '.vhdx') {
  throw "Resolved path is not a .vhdx file: $resolvedVhd"
}
if (-not (Test-Path -LiteralPath $resolvedVhd -PathType Leaf)) {
  throw "Resolved VHD does not exist: $resolvedVhd"
}

$before = (Get-Item -LiteralPath $resolvedVhd).Length
Write-Host "Distribution: $Distro"
Write-Host "VHD: $resolvedVhd"
Write-Host "Physical bytes before: $before"

if (-not $Force) {
  Write-Host 'Dry run only. Re-run with -Force to shut down WSL and compact this exact VHD.'
  exit 0
}

Write-Host 'Shutting down all WSL distributions...'
wsl.exe --shutdown
if ($LASTEXITCODE -ne 0) { throw "wsl.exe --shutdown failed with exit code $LASTEXITCODE" }

$optimize = Get-Command Optimize-VHD -ErrorAction SilentlyContinue
if ($optimize) {
  Optimize-VHD -Path $resolvedVhd -Mode Full
} else {
  $diskpartScript = [System.IO.Path]::GetTempFileName()
  try {
    @(
      "select vdisk file=`"$resolvedVhd`""
      'attach vdisk readonly'
      'compact vdisk'
      'detach vdisk'
    ) | Set-Content -LiteralPath $diskpartScript -Encoding ASCII
    diskpart.exe /s $diskpartScript
    if ($LASTEXITCODE -ne 0) { throw "diskpart compaction failed with exit code $LASTEXITCODE" }
  } finally {
    Remove-Item -LiteralPath $diskpartScript -Force -ErrorAction SilentlyContinue
  }
}

$after = (Get-Item -LiteralPath $resolvedVhd).Length
Write-Host "Physical bytes after: $after"
Write-Host "Physical bytes reclaimed: $([Math]::Max(0, $before - $after))"
