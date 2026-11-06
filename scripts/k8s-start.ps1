param([switch]$Metrics)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$env:PATH="$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin;$env:PATH"
$tools=Join-Path $workspace '.local/tools/k8s'
$private=Join-Path $workspace '.local/k8s'
$kind=Join-Path $tools 'kind.exe'
$kubectl=Join-Path $tools 'kubectl.exe'
$config=Join-Path $private 'kubeconfig'
foreach($tool in @($kind,$kubectl)){if(-not(Test-Path -LiteralPath $tool)){throw 'Install verified local kind/kubectl first; see infra/k8s/README.md.'}}
New-Item -ItemType Directory -Force $private | Out-Null
& (Join-Path $PSScriptRoot 'apps-stop.ps1')
if($LASTEXITCODE-ne0){throw 'Previous own group failed to stop'}
$nodes=@(& docker ps -aq --filter 'label=io.x-k8s.kind.cluster=internship-lab')
if($LASTEXITCODE-ne0){throw 'Cannot inspect own kind containers'}
if($nodes.Count){
 if($nodes.Count-ne1 -or -not(Test-Path -LiteralPath $config)){throw 'Unexpected existing lab; preserve and inspect manually.'}
 $name=(& docker inspect --format '{{.Name}}' $nodes[0]).Trim()
 if($name-ne'/internship-lab-control-plane'){throw 'Unexpected node; no start attempted.'}
 & docker update --restart no $nodes[0] | Out-Null
 if($LASTEXITCODE-ne0){throw 'Own lab restart policy could not be scoped'}
 & docker start $nodes[0]
 if($LASTEXITCODE-ne0){throw 'Own lab node could not resume'}
}else{
 & $kind create cluster --name internship-lab --config (Join-Path $workspace 'infra/k8s/kind.yml') --kubeconfig $config --wait 180s
 if($LASTEXITCODE-ne0){throw 'Own kind startup failed; preserve logs/state.'}
}
$deadline=(Get-Date).AddSeconds(150)
do{
 & $kubectl --kubeconfig $config --context kind-internship-lab get --raw=/readyz --request-timeout=3s *> $null
 $apiReady=$LASTEXITCODE-eq0
 if(-not $apiReady){Start-Sleep -Milliseconds 500}
}while(-not $apiReady -and (Get-Date)-lt$deadline)
if(-not $apiReady){throw 'Own lab API did not become ready'}
& $kubectl --kubeconfig $config --context kind-internship-lab wait --for=condition=Ready nodes --all --timeout=150s
if($LASTEXITCODE-ne0){throw 'Own lab node did not become ready'}
function Import-LabImage([string]$Image,[string]$File){
 & docker image inspect $Image *> $null
 if($LASTEXITCODE-ne0){& docker pull $Image;if($LASTEXITCODE-ne0){throw 'Lab image unavailable'}}
 & docker image save --platform linux/amd64 -o $File $Image
 if($LASTEXITCODE-ne0){throw 'Could not archive lab image'}
 & $kind load image-archive $File --name internship-lab
 if($LASTEXITCODE-ne0){throw 'Could not import lab image'}
}
Import-LabImage 'event-gateway:local' (Join-Path $private 'gateway-image.tar')
if($Metrics){
 $image='registry.k8s.io/metrics-server/metrics-server:v0.9.1@sha256:9b0ba1926e1d3089673f45461c3c1416109dea33b3ba3434048defd380df329b'
 Import-LabImage $image (Join-Path $private 'metrics-image.tar')
 $manifest=Get-Content -LiteralPath (Join-Path $workspace 'infra/k8s/metrics-server.upstream.yml') -Raw
 # The pinned multi-platform source is exported as one native-platform archive.
 # kind imports its version tag, not the upstream multi-platform manifest digest.
 # Never pull inside the lab; use only that explicitly imported, pinned source.
 $manifest=$manifest.Replace('imagePullPolicy: IfNotPresent','imagePullPolicy: Never')
 $manifest=$manifest.Replace('- --metric-resolution=15s',"- --metric-resolution=15s`n        - --kubelet-insecure-tls")
 $path=Join-Path $private 'metrics-server.lab.yml'
 $manifest | Set-Content -LiteralPath $path -Encoding utf8
 & $kubectl --kubeconfig $config --context kind-internship-lab apply -f $path
 if($LASTEXITCODE-ne0){throw 'Lab metrics manifest failed'}
 & $kubectl --kubeconfig $config --context kind-internship-lab -n kube-system rollout status deployment/metrics-server --timeout=150s
 if($LASTEXITCODE-ne0){throw 'Lab metrics not ready'}
}
& $kubectl --kubeconfig $config --context kind-internship-lab apply -f (Join-Path $workspace 'infra/k8s/gateway-lab.yml')
if($LASTEXITCODE-ne0){throw 'Own lab manifest failed'}
& $kubectl --kubeconfig $config --context kind-internship-lab -n internship-lab rollout status deployment/event-gateway --timeout=150s
if($LASTEXITCODE-ne0){throw 'Own lab gateway did not become ready'}
Write-Output 'Own Gateway lab ready on 127.0.0.1:18000; business backend is absent. Stop via apps-stop.ps1.'
