param([switch]$Observability)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$env:PATH="$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin;$env:PATH"
# Docker restart may automatically revive the stopped kind node. Keep this
# low-memory application group separate even when invoked without runtime.ps1.
$labNodes=@(& docker ps -q --filter 'label=io.x-k8s.kind.cluster=internship-lab')
if($LASTEXITCODE-ne0){throw 'Docker is not ready.'}
if($labNodes.Count){
 if(-not(Test-Path -LiteralPath (Join-Path $workspace '.local/k8s/kubeconfig'))){throw 'Unexpected lab without own kubeconfig; inspect manually.'}
 foreach($node in $labNodes){if($node-notmatch'^[a-f0-9]{12,64}$'){throw 'Unexpected lab identifier'}}
 & docker stop --timeout 30 @labNodes
 if($LASTEXITCODE-ne0){throw 'Own lab did not stop; no application startup attempted.'}
}
foreach($listenPort in @(8090,8092)) {
    $listener=Get-NetTCPConnection -LocalPort $listenPort -State Listen -ErrorAction SilentlyContinue
    if($listener){throw "Port $listenPort is occupied. Stop native project services before switching to containers; do not stop unknown processes."}
}
$composeArgs=@('compose','--profile','agent','--env-file',(Join-Path $workspace 'infra/.env'),'-f',(Join-Path $workspace 'infra/compose.yml'),'-f',(Join-Path $workspace 'infra/compose.apps.yml'))
if($Observability){& (Join-Path $PSScriptRoot 'init-observability.ps1');$composeArgs+=@('-f',(Join-Path $workspace 'infra/compose.observability.yml'))}
& docker @composeArgs up -d mysql redis namesrv broker postgres objectstore
if($LASTEXITCODE -ne 0){throw 'Own middleware startup failed.'}
$deadline=(Get-Date).AddSeconds(80)
do {
    $health=(& docker inspect internship-projects-mysql-1 --format '{{.State.Health.Status}}').Trim()
    if($health -eq 'healthy'){break}
    Start-Sleep -Milliseconds 500
}while((Get-Date) -lt $deadline)
if($health -ne 'healthy'){throw 'Own MySQL is not healthy; no schema change was attempted.'}
Get-Content -LiteralPath (Join-Path $workspace 'infra/mysql/02-app-database.sql') -Raw | & docker exec -i internship-projects-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot'
if($LASTEXITCODE -ne 0){throw 'Own Docker application schema initialization failed.'}
& docker @composeArgs up -d event-app repo-agent
if($LASTEXITCODE -ne 0){throw 'Application startup failed.'}
if($Observability){& docker @composeArgs up -d otel-collector tempo prometheus grafana;if($LASTEXITCODE -ne 0){throw 'Observability group startup failed.'}}
$deadline=(Get-Date).AddSeconds(120)
$ready=$false
do {
    try {
        $java=Invoke-RestMethod 'http://127.0.0.1:8090/actuator/health' -TimeoutSec 3
        $agent=Invoke-RestMethod 'http://127.0.0.1:8092/health' -TimeoutSec 3
        $ready=$java.status -eq 'UP' -and $agent.status -eq 'up'
    } catch {$ready=$false}
    if(-not $ready){Start-Sleep -Milliseconds 500}
}while(-not $ready -and (Get-Date) -lt $deadline)
if(-not $ready){throw 'Application did not become ready. Inspect this project container logs before sending tasks.'}
Write-Output 'Application ready: http://127.0.0.1:8090/ and http://127.0.0.1:8092/health.'
Write-Output 'Use only one native/container Agent writer: both share the cumulative local model-cost ledger.'
