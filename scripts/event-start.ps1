param([switch]$LocalMessaging)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
foreach($configLine in [IO.File]::ReadAllLines((Join-Path $workspace 'infra/.env'))) {
    if($configLine -match '^([A-Z][A-Z0-9_]*)=(.*)$') {[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}
}
$env:EVENT_MESSAGING=if($LocalMessaging){'local'}else{'rocketmq'}
Push-Location (Join-Path $workspace 'event-platform')
try {
    $jar=Join-Path (Get-Location) 'target/event-platform-0.1.0.jar'
    if(-not (Test-Path -LiteralPath $jar)){throw 'Run mvn package -DskipTests first.'}
    # Run a copy: Windows locks a running jar, but target must remain buildable.
    $runtimeDirectory=Join-Path $workspace '.local/runtime'
    New-Item -ItemType Directory -Force -Path $runtimeDirectory | Out-Null
    $runtimeJar=Join-Path $runtimeDirectory 'event-platform.jar'
    Copy-Item -LiteralPath $jar -Destination $runtimeJar -Force
    & java -Xms128m -Xmx384m -jar $runtimeJar
} finally {Pop-Location}
