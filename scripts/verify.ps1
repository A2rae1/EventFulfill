$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$logs=Join-Path $workspace '.local/verification'
New-Item -ItemType Directory -Force $logs | Out-Null
$results=[Collections.Generic.List[object]]::new()
function Invoke-ProjectCheck([string]$Name,[scriptblock]$Action){
 $started=Get-Date
 & $Action *> (Join-Path $logs "$Name.log")
 $code=$LASTEXITCODE
 $results.Add(@{name=$Name;exit_code=$code;seconds=[math]::Round(((Get-Date)-$started).TotalSeconds,2)})
 $results | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $logs 'results.json')
 if($code-ne 0){throw "$Name failed; inspect .local/verification/$Name.log"}
 Write-Output "$Name passed"
}
Push-Location $workspace
try{
 Invoke-ProjectCheck 'python-lint' {uv run --project repo-agent --frozen ruff check repo-agent/src repo-agent/tests repo-agent/evaluation repo-agent/scripts}
 Invoke-ProjectCheck 'python-tests' {uv run --project repo-agent --frozen pytest repo-agent/tests -q}
 Invoke-ProjectCheck 'java-package' {mvn -B -ntp -f event-platform/pom.xml verify}
 Invoke-ProjectCheck 'gateway-package' {mvn -B -ntp -f event-platform/gateway/pom.xml verify}
 Write-Output 'Local verification pipeline passed. Conditional real-dependency skips remain explicit; this is not a remote CI run.'
}finally{Pop-Location}
