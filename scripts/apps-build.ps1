$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$env:PATH="$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin;$env:PATH"
& (Join-Path $PSScriptRoot 'apps-stop.ps1')
if($LASTEXITCODE-ne0){throw 'Stop own runtime groups before building images.'}
$imageLock=[ordered]@{}
$lockPath=Join-Path $workspace 'infra/image-lock.json'
if(Test-Path -LiteralPath $lockPath){
 $existingLock=Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json -AsHashtable
 foreach($entry in $existingLock.GetEnumerator()){$imageLock[$entry.Key]=$entry.Value}
}
foreach($imageTag in @('eclipse-temurin:17-jre-jammy','python:3.12-slim-bookworm','docker:29-cli')) {
    $requested=if($imageLock.Contains($imageTag)){$imageLock[$imageTag]}else{$imageTag}
    & docker pull $requested
    if($LASTEXITCODE -ne 0){throw 'Official base image pull failed.'}
    $pinnedImage=(& docker image inspect $requested --format '{{index .RepoDigests 0}}').Trim()
    if($pinnedImage -notmatch '@sha256:[a-f0-9]{64}$'){throw 'Base image digest missing.'}
    $imageLock[$imageTag]=$pinnedImage
}
$imageLock | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $workspace 'infra/image-lock.json') -Encoding utf8
& docker build --build-arg "JAVA_RUNTIME=$($imageLock['eclipse-temurin:17-jre-jammy'])" -t eventfulfill:local (Join-Path $workspace 'event-platform')
if($LASTEXITCODE -ne 0){throw 'Java application image failed.'}
& docker build --build-arg "JAVA_RUNTIME=$($imageLock['eclipse-temurin:17-jre-jammy'])" -t event-gateway:local (Join-Path $workspace 'event-platform/gateway')
if($LASTEXITCODE -ne 0){throw 'Gateway image failed.'}
& docker build --build-arg "PYTHON_RUNTIME=$($imageLock['python:3.12-slim-bookworm'])" --build-arg "DOCKER_CLI=$($imageLock['docker:29-cli'])" -t repolens:local (Join-Path $workspace 'repo-agent')
if($LASTEXITCODE -ne 0){throw 'Python application image failed.'}
