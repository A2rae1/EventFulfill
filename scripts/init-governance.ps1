$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$configPath=Join-Path $workspace 'infra/.env'
$lines=[IO.File]::ReadAllLines($configPath)
foreach($name in @('NACOS_PASSWORD','NACOS_AUTH_TOKEN','NACOS_IDENTITY_VALUE')) {
 $existing=$lines | Where-Object {$_ -match "^$name="} | Select-Object -First 1
 if($existing){$secret=$existing.Substring($name.Length+1)}else{
  $secret=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(36))
  [IO.File]::AppendAllText($configPath,"`n$name=$secret`n",[Text.UTF8Encoding]::new($false))
 }
 [Environment]::SetEnvironmentVariable($name,$secret,'Process')
}
Write-Output 'Private registry configuration created/reused without printing secrets.'
