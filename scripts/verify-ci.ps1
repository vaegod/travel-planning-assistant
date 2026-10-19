# Local counterpart of the GitHub quality job. Requires the isolated CI MySQL on 33306.
$ErrorActionPreference='Stop'
$projectDir=Split-Path -Parent $PSScriptRoot
$env:DB_TEST_URL='jdbc:mysql://127.0.0.1:33306/travel_ai_test?serverTimezone=UTC&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false'
$env:DB_USERNAME='travel_ci'
$env:DB_PASSWORD='ci-ephemeral-test-only'
$env:LLM_API_KEY='fixture-only-key'
$env:LLM_MODEL='fixture-model'
Push-Location $projectDir
try {
  Push-Location travel-h5-java
  try {& npm.cmd ci;if($LASTEXITCODE){throw 'npm ci failed'};& npm.cmd test;if($LASTEXITCODE){throw 'Frontend tests failed'};& npm.cmd run build;if($LASTEXITCODE){throw 'Frontend build failed'}} finally {Pop-Location}
  Push-Location travel-java-server
  try {& .\mvnw.cmd verify -B -ntp;if($LASTEXITCODE){throw 'Backend tests/build failed'}} finally {Pop-Location}
  & node.exe scripts/evaluate.mjs
  if($LASTEXITCODE){throw 'Fixed evaluation failed'}
} finally {Pop-Location}
