$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$configPath=Join-Path $workspace 'infra/.env'
$configLines=[IO.File]::ReadAllLines($configPath)
foreach($name in @('METRICS_PASSWORD','GRAFANA_ADMIN_PASSWORD')) {
    $existing=$configLines | Where-Object {$_ -match "^$name="} | Select-Object -First 1
    if($existing){$secret=$existing.Substring($name.Length+1)}else{
        $secret=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(36))
        [IO.File]::AppendAllText($configPath,"`n$name=$secret`n",[Text.UTF8Encoding]::new($false))
    }
    [Environment]::SetEnvironmentVariable($name,$secret,'Process')
}
$privateDirectory=Join-Path $workspace '.local/observability'
New-Item -ItemType Directory -Path $privateDirectory -Force | Out-Null
$prometheus=@"
global:
  scrape_interval: 5s
scrape_configs:
  - job_name: event-app
    metrics_path: /actuator/prometheus
    basic_auth:
      username: metrics
      password: '$env:METRICS_PASSWORD'
    static_configs:
      - targets: ['event-app:8090']
  - job_name: repo-agent
    metrics_path: /metrics
    basic_auth:
      username: metrics
      password: '$env:METRICS_PASSWORD'
    static_configs:
      - targets: ['repo-agent:8092']
"@
[IO.File]::WriteAllText((Join-Path $privateDirectory 'prometheus.yml'),$prometheus,[Text.UTF8Encoding]::new($false))
Write-Output 'Private metrics/dashboard credentials configured; no credentials printed.'
