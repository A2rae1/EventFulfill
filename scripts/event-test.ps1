param([switch]$MySql)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
if($MySql) {
    # Consume only the configuration generated for this project; never print secrets.
    $configPath=Join-Path $workspace 'infra/.env'
    foreach($configLine in [IO.File]::ReadAllLines($configPath)) {
        if($configLine -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')
        }
    }
    $env:EVENT_MYSQL_IT='true'
}
Push-Location (Join-Path $workspace 'event-platform')
try {
    & mvn -B -ntp test
    if($LASTEXITCODE -ne 0) { throw 'Event tests failed. See target/surefire-reports.' }
} finally { Pop-Location }
