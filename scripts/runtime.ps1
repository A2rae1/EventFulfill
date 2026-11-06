param([ValidateSet('core','agent','search','apps','observe','governance','jobs','stop')][string]$Group='core')
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$env:PATH="$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin;$env:PATH"
$compose=Join-Path $workspace 'infra/compose.yml'
$config=Join-Path $workspace 'infra/.env'
# Only this compose project is affected. Named volumes are retained.
& (Join-Path $PSScriptRoot 'apps-stop.ps1')
if($LASTEXITCODE -ne 0){throw 'Could not stop this project group cleanly.'}
if($Group -eq 'stop'){exit 0}
if($Group -eq 'governance'){& (Join-Path $PSScriptRoot 'governance-start.ps1');exit $LASTEXITCODE}
if($Group -eq 'jobs'){& (Join-Path $PSScriptRoot 'jobs-start.ps1');exit $LASTEXITCODE}
if($Group -in @('apps','observe')){& (Join-Path $PSScriptRoot 'apps-start.ps1') -Observability:($Group -eq 'observe');exit $LASTEXITCODE}
$services=switch($Group){
  'core' {@('mysql','redis','namesrv','broker')}
  'agent' {@('mysql','redis','namesrv','broker','postgres','objectstore')}
  'search' {@('elasticsearch','objectstore')}
}
& docker compose --progress quiet --profile agent --profile search --env-file $config -f $compose up -d @services
if($LASTEXITCODE -ne 0){throw 'Project group startup failed.'}
& docker stats --no-stream --format '{{.Name}} {{.MemUsage}}'
