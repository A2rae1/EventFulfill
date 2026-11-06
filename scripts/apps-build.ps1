$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$env:PATH="$env:LOCALAPPDATA/Programs/DockerDesktop/resources/bin;$env:PATH"
$lock=Get-Content -LiteralPath (Join-Path $workspace 'infra/image-lock.json') -Raw | ConvertFrom-Json -AsHashtable
foreach($entry in @(@{Tag='eventfulfill:local';Dir='event-platform'},@{Tag='event-gateway:local';Dir='event-platform/gateway'})) {
 & docker build --build-arg "JAVA_RUNTIME=$($lock['eclipse-temurin:17-jre-jammy'])" -t $entry.Tag (Join-Path $workspace $entry.Dir)
 if($LASTEXITCODE-ne0){throw 'Java image build failed.'}
}
