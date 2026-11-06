param([switch]$Extended,[switch]$StreamProbe,[switch]$RedisOutage,[switch]$EventLifecycle,[switch]$Observability,[switch]$LiveTrace,[switch]$RestartProbe)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
foreach($configLine in [IO.File]::ReadAllLines((Join-Path $workspace 'infra/.env'))) {
    if($configLine -match '^([A-Z][A-Z0-9_]*)=(.*)$') {[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}
}
$env:PYTHONPATH=Join-Path $workspace 'repo-agent/src'
$env:PYTHONIOENCODING='utf-8'
Push-Location (Join-Path $workspace 'repo-agent')
try {if($LiveTrace){& uv run python scripts/observability_probe.py --live}else{& uv run python $(if($RestartProbe){'scripts/checkpoint_restart_probe.py'}elseif($Observability){'scripts/observability_probe.py'}elseif($EventLifecycle){'scripts/event_lifecycle_probe.py'}elseif($RedisOutage){'scripts/redis_outage_probe.py'}elseif($StreamProbe){'scripts/proxy_stream_probe.py'}elseif($Extended){'scripts/e2e_extended.py'}else{'scripts/e2e_live.py'})}; if($LASTEXITCODE -ne 0){throw 'Real cross-service check failed; inspect saved task events.'}} finally {Pop-Location}
