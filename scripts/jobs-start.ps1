$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$env:PATH="$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin;$env:PATH"
$env:PYTHONUTF8='1'
& (Join-Path $PSScriptRoot 'apps-stop.ps1')
$argsCompose=@('compose','--profile','agent','--env-file',(Join-Path $workspace 'infra/.env'),'-f',(Join-Path $workspace 'infra/compose.yml'),'-f',(Join-Path $workspace 'infra/compose.apps.yml'),'-f',(Join-Path $workspace 'infra/compose.jobs.yml'))
& docker @argsCompose up -d mysql redis namesrv broker
if($LASTEXITCODE-ne 0){throw 'Own job middleware failed'}
$deadline=(Get-Date).AddSeconds(90)
do{$health=(& docker inspect internship-projects-mysql-1 --format '{{.State.Health.Status}}');if($health.Trim()-eq'healthy'){break};Start-Sleep -Milliseconds 500}while((Get-Date)-lt$deadline)
if($health.Trim()-ne'healthy'){throw 'Own MySQL not ready'}
& (Join-Path $PSScriptRoot 'init-jobs.ps1')
& docker @argsCompose run --rm --no-deps --user 0 --entrypoint sh event-app -c 'mkdir -p /tmp/xxl-job; chown -R 10001:10001 /tmp/xxl-job'
if($LASTEXITCODE-ne 0){throw 'Own managed log volume initialization failed'}
& docker @argsCompose up -d --no-deps xxl-job event-app
if($LASTEXITCODE-ne 0){throw 'Own scheduler/app failed'}
$deadline=(Get-Date).AddSeconds(120)
do{
 try{$java=Invoke-RestMethod 'http://127.0.0.1:8090/actuator/health' -TimeoutSec 2;$admin=Invoke-WebRequest 'http://127.0.0.1:18080/xxl-job-admin/toLogin' -TimeoutSec 2;if($java.status-eq'UP'-and$admin.StatusCode-eq200){Write-Output 'Managed scheduler group ready; internal scanner disabled, task initially stopped, AI worker stopped.';exit 0}}catch{}
 Start-Sleep -Milliseconds 500
}while((Get-Date)-lt$deadline)
throw 'Managed scheduler not ready; inspect project logs.'
