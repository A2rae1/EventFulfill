$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
function New-LocalSecret { [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant() }
$infraEnv = Join-Path $workspace 'infra/.env'
if (-not (Test-Path -LiteralPath $infraEnv)) {
    $dbSecret = New-LocalSecret
    $jwtSecret = New-LocalSecret
    $paymentSecret = New-LocalSecret
    $demoSecret = New-LocalSecret
    $checkpointSecret = New-LocalSecret
    $objectSecret = New-LocalSecret
    @(
        "MYSQL_ROOT_PASSWORD=$(New-LocalSecret)",
        "EVENT_DB_PASSWORD=$dbSecret",
        "EVENT_JWT_SECRET=$jwtSecret",
        "EVENT_PAYMENT_SECRET=$paymentSecret",
        'EVENT_DEMO_USERS=true',
        "EVENT_DEMO_PASSWORD=$demoSecret",
        "AGENT_CHECKPOINT_PASSWORD=$checkpointSecret",
        "AGENT_OBJECT_PASSWORD=$objectSecret"
    ) | Set-Content -LiteralPath $infraEnv -Encoding utf8
    Write-Output 'Created infra/.env. Secrets were generated locally and are not printed.'
} else { Write-Output 'infra/.env already exists; left unchanged.' }
# Add new project-owned settings without replacing existing credentials.
$localLines=[IO.File]::ReadAllLines($infraEnv)
if(-not ($localLines | Where-Object { $_ -match '^AGENT_INTERNAL_SECRET=' })) {
    Add-Content -LiteralPath $infraEnv -Value "AGENT_INTERNAL_SECRET=$(New-LocalSecret)" -Encoding utf8
}
