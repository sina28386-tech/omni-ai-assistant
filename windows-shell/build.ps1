$ErrorActionPreference = 'Stop'
$shellRoot = $PSScriptRoot
$desktopRoot = Join-Path (Split-Path -Parent $shellRoot) 'desktop'
$source = Join-Path $desktopRoot 'dist'
$runtime = Join-Path $shellRoot 'runtime'
if (-not (Test-Path -LiteralPath (Join-Path $source 'server\index.js'))) { throw 'Build the desktop project first.' }
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $runtime 'dist') -Force | Out-Null
Get-ChildItem -LiteralPath $source -Force | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $runtime 'dist') -Recurse -Force }
Set-Content -LiteralPath (Join-Path $runtime 'package.json') -Value '{"type":"module"}' -Encoding Ascii
$env:CSC_IDENTITY_AUTO_DISCOVERY = 'false'
& (Join-Path $shellRoot 'node_modules\.bin\electron-builder.cmd') --win nsis --x64 --publish never
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
