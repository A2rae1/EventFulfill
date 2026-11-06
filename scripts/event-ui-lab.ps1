param([int]$Port=8094)
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
foreach($configLine in [IO.File]::ReadAllLines((Join-Path $workspace 'infra/.env'))) {
    if($configLine -match '^([A-Z][A-Z0-9_]*)=(.*)$') {[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}
}
$env:APP_DEMO_USERS='true'
$env:APP_DEMO_PASSWORD='ui-lab-only-2026'
$env:APP_JOBS_ENABLED='true'
$env:APP_MESSAGING='local'
$env:APP_REDIS_ENABLED='false'
Write-Output 'UI lab uses a temporary in-memory H2 database. This is not a MySQL/MQ acceptance run.'
Push-Location (Join-Path $workspace 'event-platform')
try {
    $reportXml=[xml](Get-Content -LiteralPath 'target/surefire-reports/TEST-cn.hm.event.ApiSecurityTest.xml' -Raw)
    $testClasspath=($reportXml.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
    if(-not $testClasspath){throw 'Run mvn test first to prepare the test classpath.'}
    & java -Xms96m -Xmx320m -cp $testClasspath cn.hm.event.EventApplication --spring.profiles.active=test --server.port=$Port
} finally {Pop-Location}
