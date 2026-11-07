$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
& node --test (Join-Path $workspace 'event-platform/ui-tests/template-expressions.test.mjs')
if($LASTEXITCODE-ne0){throw 'Page template verification failed.'}
foreach($project in @('event-platform','event-platform/gateway')) {
 & mvn -B -ntp -f (Join-Path $workspace "$project/pom.xml") verify
 if($LASTEXITCODE-ne0){throw "Verification failed: $project"}
}
