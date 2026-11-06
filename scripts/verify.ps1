$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
foreach($project in @('event-platform','event-platform/gateway')) {
 & mvn -B -ntp -f (Join-Path $workspace "$project/pom.xml") verify
 if($LASTEXITCODE-ne0){throw "Verification failed: $project"}
}
