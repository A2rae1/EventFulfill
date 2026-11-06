$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$env:PATH="$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin;$env:PATH"
$owned=@(& docker ps -q --filter 'label=com.docker.compose.project=internship-projects')
if($LASTEXITCODE -ne 0){throw 'Could not enumerate this project containers.'}
if(Test-Path -LiteralPath (Join-Path $workspace '.local/k8s/kubeconfig')){
 $owned+=@(& docker ps -q --filter 'label=io.x-k8s.kind.cluster=internship-lab')
 if($LASTEXITCODE-ne0){throw 'Could not enumerate own lab node'}
}
if($owned.Count -gt 0){
 foreach($id in $owned){if($id -notmatch '^[a-f0-9]{12,64}$'){throw 'Unexpected container identifier; no stop attempted.'}}
 & docker stop --timeout 30 @owned
 if($LASTEXITCODE -ne 0){throw 'Own application group did not stop cleanly.'}
}
Write-Output 'Project application containers stopped; volumes, images, model cache and the old Windows MySQL are retained.'
