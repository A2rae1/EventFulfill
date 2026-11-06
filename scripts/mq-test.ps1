$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
foreach($configLine in [IO.File]::ReadAllLines((Join-Path $workspace 'infra/.env'))) {
 if($configLine -match '^([A-Z][A-Z0-9_]*)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}
}
$env:EVENT_MQ_IT='true'
Push-Location (Join-Path $workspace 'event-platform')
try {& mvn -B -ntp '-Dtest=RocketMqIntegrationTest,MqRecoveryIntegrationTest' test; if($LASTEXITCODE -ne 0){throw 'Real MQ integration failed; see test reports.'}}finally{Pop-Location}
