param([switch]$Postgres,[switch]$Search)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
foreach($configLine in [IO.File]::ReadAllLines((Join-Path $workspace 'infra/.env'))) {
    if($configLine -match '^([A-Z][A-Z0-9_]*)=(.*)$') {[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}
}
if($Postgres){$env:CHECKPOINT_URL="postgresql://repoagent:$($env:AGENT_CHECKPOINT_PASSWORD)@127.0.0.1:15432/repoagent"}
$env:OBJECT_SECRET_KEY=$env:AGENT_OBJECT_PASSWORD
$env:DOCKER_EXECUTABLE=Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe'
$env:PYTHONPATH=Join-Path $workspace 'repo-agent/src'
$env:PYTHONIOENCODING='utf-8'
Push-Location (Join-Path $workspace 'repo-agent')
try {
 if($Search){$env:EMBEDDING_ENABLED='true';$env:SEARCH_BACKEND='hybrid';& uv run --extra embedding uvicorn repo_agent.app:create_app --factory --host 127.0.0.1 --port 8092}
 else{& uv run uvicorn repo_agent.app:create_app --factory --host 127.0.0.1 --port 8092}
} finally {Pop-Location}
