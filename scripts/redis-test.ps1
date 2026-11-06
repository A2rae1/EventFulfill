$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$env:EVENT_REDIS_IT='true'
Push-Location (Join-Path $workspace 'event-platform')
try {& mvn -B -ntp '-Dtest=RedisIntegrationTest' test; if($LASTEXITCODE -ne 0){throw 'Real Redis integration failed.'}}finally{Pop-Location}
