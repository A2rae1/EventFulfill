param([ValidateSet('core','apps','observe','governance','jobs','stop')][string]$Group='core')
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$env:PATH="$env:LOCALAPPDATA/Programs/DockerDesktop/resources/bin;$env:PATH"
if($Group-eq'stop'){& "$PSScriptRoot/apps-stop.ps1";exit $LASTEXITCODE}
if($Group-eq'governance'){& "$PSScriptRoot/governance-start.ps1";exit $LASTEXITCODE}
if($Group-eq'jobs'){& "$PSScriptRoot/jobs-start.ps1";exit $LASTEXITCODE}
if($Group-in@('apps','observe')){& "$PSScriptRoot/apps-start.ps1" -Observability:($Group-eq'observe');exit $LASTEXITCODE}
& docker compose --env-file (Join-Path $workspace 'infra/.env') -f (Join-Path $workspace 'infra/compose.yml') up -d mysql redis namesrv broker
if($LASTEXITCODE-ne0){throw 'Core middleware startup failed.'}
