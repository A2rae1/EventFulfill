param([ValidatePattern('^[a-z0-9][a-z0-9_-]{0,40}$')][string]$ProjectName='eventfulfill',[switch]$Observability,[switch]$Stop)
$ErrorActionPreference='Stop';$workspace=Split-Path -Parent $PSScriptRoot
$env:PATH="$env:LOCALAPPDATA/Programs/DockerDesktop/resources/bin;$env:PATH"
if(-not$Stop){& "$PSScriptRoot/init-local.ps1"}
$c=@('compose','--project-name',$ProjectName,'--env-file',(Join-Path $workspace 'infra/.env'),'-f',(Join-Path $workspace 'infra/compose.yml'),'-f',(Join-Path $workspace 'infra/compose.apps.yml'))
if($Observability){& "$PSScriptRoot/init-observability.ps1";$c+=@('-f',(Join-Path $workspace 'infra/compose.observability.yml'))}
if($Stop){$owned=@(& docker ps -q --filter "label=com.docker.compose.project=$ProjectName");if($LASTEXITCODE-ne0){throw 'Project lookup failed'};if($owned.Count){& docker stop --timeout 30 @owned};exit $LASTEXITCODE}
& docker @c up -d mysql redis namesrv broker
if($LASTEXITCODE-ne0){throw 'EventFulfill middleware startup failed'}
$deadline=(Get-Date).AddSeconds(100)
do{$health=(& docker inspect "$ProjectName-mysql-1" --format '{{.State.Health.Status}}');if($health.Trim()-eq'healthy'){break};Start-Sleep -Milliseconds 500}while((Get-Date)-lt$deadline)
if($health.Trim()-ne'healthy'){throw 'Own MySQL not healthy'}
Get-Content (Join-Path $workspace 'infra/mysql/02-app-database.sql') -Raw|& docker exec -i "$ProjectName-mysql-1" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot'
if($LASTEXITCODE-ne0){throw 'Application schema initialization failed'}
& docker @c up -d event-app
if($LASTEXITCODE-ne0){throw 'EventFulfill startup failed'}
if($Observability){& docker @c up -d otel-collector tempo prometheus grafana;if($LASTEXITCODE-ne0){throw 'Observation startup failed'}}
$ready=$false;$deadline=(Get-Date).AddSeconds(120)
do{try{$h=Invoke-RestMethod 'http://127.0.0.1:8090/actuator/health' -TimeoutSec 3;$ready=$h.status-eq'UP'}catch{};if(-not$ready){Start-Sleep -Milliseconds 500}}while(-not$ready-and(Get-Date)-lt$deadline)
if(-not$ready){throw 'EventFulfill did not become healthy'}
Write-Output 'EventFulfill ready on8090; no RepoLens service required.'
