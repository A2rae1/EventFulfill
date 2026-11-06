$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$config=Join-Path $workspace 'infra/.env'
foreach($name in @('XXL_ADMIN_PASSWORD','XXL_ACCESS_TOKEN')){
 $line=[IO.File]::ReadAllLines($config)|Where-Object {$_ -match "^$name="}|Select-Object -First 1
 if($line){$value=$line.Substring($name.Length+1)}else{$value=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24));[IO.File]::AppendAllText($config,"`n$name=$value`n")}
 [Environment]::SetEnvironmentVariable($name,$value,'Process')
}
Push-Location (Join-Path $workspace 'repo-agent')
try{& uv run python scripts/jobs_init.py;if($LASTEXITCODE-ne 0){throw 'Own scheduler database initialization failed.'}}finally{Pop-Location}
