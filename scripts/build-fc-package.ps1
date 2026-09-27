param(
    [string]$PythonExe = ".\.venv\Scripts\python.exe",
    [string]$OutputName = "easyaccess-fc-python310.zip"
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$tmpRoot = Join-Path $repoRoot ".tmp"
$packageRoot = Join-Path $tmpRoot "fc-package-python310"
$artifactRoot = Join-Path $repoRoot "artifacts"
$outputPath = Join-Path $artifactRoot $OutputName
$expectedPackageRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $repoRoot ".tmp\fc-package-python310")
)

if ([System.IO.Path]::GetFullPath($packageRoot) -ne $expectedPackageRoot) {
    throw "Package path validation failed."
}

$resolvedPython = if ([System.IO.Path]::IsPathRooted($PythonExe)) {
    $PythonExe
} else {
    Join-Path $repoRoot $PythonExe
}

if (-not (Test-Path -LiteralPath $resolvedPython)) {
    throw "Python executable not found: $resolvedPython"
}

if (Test-Path -LiteralPath $packageRoot) {
    Remove-Item -LiteralPath $packageRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $packageRoot -Force | Out-Null
New-Item -ItemType Directory -Path $artifactRoot -Force | Out-Null

& $resolvedPython -m pip install `
    --requirement (Join-Path $repoRoot "backend\requirements-fc.txt") `
    --target $packageRoot `
    --platform manylinux2014_x86_64 `
    --implementation cp `
    --python-version 3.10 `
    --only-binary=:all: `
    --upgrade
if ($LASTEXITCODE -ne 0) {
    throw "Dependency installation failed."
}

Copy-Item -LiteralPath (Join-Path $repoRoot "backend\app") -Destination $packageRoot -Recurse -Force
Copy-Item -LiteralPath (Join-Path $repoRoot "backend\server.py") -Destination $packageRoot -Force

$zipCode = @'
import os
import sys
import zipfile

source, output = map(os.path.abspath, sys.argv[1:3])
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
    for directory, _, files in os.walk(source):
        for filename in files:
            path = os.path.join(directory, filename)
            relative = os.path.relpath(path, source).replace(os.sep, "/")
            archive.write(path, relative)
'@

& $resolvedPython -c $zipCode $packageRoot $outputPath
if ($LASTEXITCODE -ne 0) {
    throw "ZIP creation failed."
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($outputPath)
try {
    $entries = $archive.Entries.FullName
    if ($entries -notcontains "server.py") {
        throw "server.py is missing from the package."
    }
    if ($entries -notcontains "exceptiongroup/__init__.py") {
        throw "exceptiongroup is missing from the package."
    }
    if (($entries | Where-Object { $_ -match "\\" }).Count -ne 0) {
        throw "The ZIP contains Windows-style paths and cannot be deployed safely."
    }
} finally {
    $archive.Dispose()
}

Get-Item -LiteralPath $outputPath | Select-Object FullName, Length, LastWriteTime
Get-FileHash -Algorithm SHA256 -LiteralPath $outputPath | Select-Object Hash
