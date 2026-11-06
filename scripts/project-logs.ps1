param([Parameter(Mandatory=$true)][string]$Container,[int]$Tail=40)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$docker=Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe'
$project=& $docker inspect $Container --format '{{index .Config.Labels "com.docker.compose.project"}}'
if($project.Trim() -ne 'internship-projects'){throw 'Only this project container logs may be read by this helper.'}
$logText=(& $docker logs --tail $Tail $Container 2>&1 | Out-String)
foreach($line in [IO.File]::ReadAllLines((Join-Path $workspace 'infra/.env'))){
 if($line -match '^[A-Z][A-Z0-9_]*=(.+)$'){
  $secret=$Matches[1]
  if($secret.Length -ge 16){foreach($representation in @($secret,[Uri]::EscapeDataString($secret),[Net.WebUtility]::UrlEncode($secret))){$logText=$logText.Replace($representation,'[REDACTED]')}}
 }
}
$logText=[regex]::Replace($logText,'[A-Za-z0-9_-]{16,}\.[A-Za-z0-9_-]{16,}\.[A-Za-z0-9_-]{16,}','[REDACTED_JWT]')
Write-Output $logText
