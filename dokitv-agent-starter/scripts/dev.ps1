param(
    [ValidateSet('run','test','package')][string]$Action = 'run',
    [string]$JavaHome
)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$jdkCandidates = @()
if ($JavaHome) { $jdkCandidates += $JavaHome }
elseif ($env:JAVA_HOME) { $jdkCandidates += $env:JAVA_HOME }
if (!$JavaHome) {
    $jdkRoot = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path -LiteralPath $jdkRoot) {
        $jdkCandidates += Get-ChildItem -LiteralPath $jdkRoot -Directory | Select-Object -ExpandProperty FullName
    }
}
$selectedJdk = $jdkCandidates | Where-Object {
    $releaseFile = Join-Path $_ 'release'
    (Test-Path -LiteralPath $releaseFile) -and
        ((Get-Content -LiteralPath $releaseFile) -match '^JAVA_VERSION="17\.')
} | Select-Object -First 1
if (!$selectedJdk) { throw 'JDK 17 was not found. Pass -JavaHome with the installed JDK 17 directory.' }
$env:JAVA_HOME = $selectedJdk
$env:PATH = "$selectedJdk\bin;$env:PATH"
Push-Location -LiteralPath $projectPath
try {
    Write-Host "Using JDK: $selectedJdk"
    switch ($Action) {
        'run' { & mvn spring-boot:run }
        'test' { & mvn -B test }
        'package' { & mvn -B package }
    }
    if ($LASTEXITCODE -ne 0) { throw "Maven failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
