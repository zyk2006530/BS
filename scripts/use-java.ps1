# Run from any directory; dot-source to configure this PowerShell session only.
# . ./scripts/use-java.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$local = Join-Path $root '.local'
$javaDirectory = Join-Path $local 'zulu17.62.17-ca-jdk17.0.17-win_x64'
$javaExecutable = Join-Path $javaDirectory 'bin/java.exe'
if (-not (Test-Path $javaExecutable)) {
    New-Item -ItemType Directory -Force $local | Out-Null
    $archive = Join-Path $local 'zulu.zip'
    $expected = 'bd8a942bb543f109a28d3eadf3ec2f29a3ee28ab53506e31d2858292f63c6949'
    $url = 'https://cdn.azul.com/zulu/bin/zulu17.62.17-ca-jdk17.0.17-win_x64.zip'
    if (-not (Test-Path $archive)) {
        Invoke-WebRequest -Uri $url -OutFile $archive
    }
    if ((Get-FileHash $archive -Algorithm SHA256).Hash -ne $expected) {
        throw "Java archive checksum mismatch: $archive. Do not extract it."
    }
    Expand-Archive -Path $archive -DestinationPath $local -Force
}
$env:JAVA_HOME = $javaDirectory
$env:PATH = "$javaDirectory\bin;$env:PATH"
& $javaExecutable -version
if ($LASTEXITCODE -ne 0) { throw 'Pinned Java could not start.' }
Write-Host 'Configured Java 17.0.17+10 for this PowerShell session; no system settings changed.'
