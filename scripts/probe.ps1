param([ValidateSet('event_lifecycle_probe','redis_outage_probe','load_probe','mysql_diagnostics','jobs_probe','governance_probe','jvm_diagnostics','k8s_probe')][string]$Probe='event_lifecycle_probe',[ValidatePattern('^[a-z0-9][a-z0-9_-]{0,40}$')][string]$ProjectName='internship-projects')
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$env:E2E_COMPOSE_PROJECT=$ProjectName
foreach($line in [IO.File]::ReadAllLines((Join-Path $workspace 'infra/.env'))){if($line-match'^([A-Z][A-Z0-9_]*)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
New-Item -ItemType Directory -Force (Join-Path $workspace '.local')|Out-Null
Push-Location $workspace
try{& uv run --with httpx==0.28.1 python "tools/probes/$Probe.py";if($LASTEXITCODE-ne0){throw 'Java operational probe failed.'}}finally{Pop-Location}
