$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$env:PATH="$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin;$env:PATH"
& (Join-Path $PSScriptRoot 'init-governance.ps1')
# A resource-scoped Java governance experiment. Existing AI task state is retained.
& (Join-Path $PSScriptRoot 'apps-stop.ps1')
if($LASTEXITCODE -ne 0){throw 'Could not stop own previous group.'}
$argsCompose=@('compose','--profile','agent','--env-file',(Join-Path $workspace 'infra/.env'),'-f',(Join-Path $workspace 'infra/compose.yml'),'-f',(Join-Path $workspace 'infra/compose.apps.yml'),'-f',(Join-Path $workspace 'infra/compose.governance.yml'))
& docker @argsCompose up -d nacos mysql redis namesrv broker
if($LASTEXITCODE -ne 0){throw 'Governance middleware failed.'}
Push-Location (Join-Path $workspace 'repo-agent')
try{& uv run python scripts/nacos_init.py;if($LASTEXITCODE -ne 0){throw 'Own registry initialization failed.'}}finally{Pop-Location}
function Wait-ProjectHealth([string]$Uri){
 $deadline=(Get-Date).AddSeconds(120)
 do{try{$body=Invoke-RestMethod $Uri -TimeoutSec 3;if($body.status -eq 'UP'){return}}catch{};Start-Sleep -Milliseconds 500}while((Get-Date)-lt$deadline)
 throw 'Own service failed readiness; inspect its project logs.'
}
foreach($entry in @(@{Name='event-app';Url='http://127.0.0.1:8090/actuator/health'},@{Name='event-app-2';Url='http://127.0.0.1:8093/actuator/health'},@{Name='gateway';Url='http://127.0.0.1:8080/actuator/health'})){
 & docker @argsCompose up -d --no-deps $entry.Name
 if($LASTEXITCODE -ne 0){throw 'Own application startup failed.'}
 Wait-ProjectHealth $entry.Url
}
Write-Output 'Java governance group ready: gateway8080, two backend instances, private Nacos18848/18880. AI worker is stopped in this resource-scoped experiment.'
