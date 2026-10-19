param([switch]$Build, [switch]$SkipDatabase)
$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
$backendDir = Join-Path $projectDir 'travel-java-server'
$frontendDir = Join-Path $projectDir 'travel-h5-java'
$runtimeDir = Join-Path $projectDir '.runtime'
New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null

foreach ($port in @(3200, 5173)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        throw "端口 $port 已被占用。请确认已有服务，或运行本项目 stop.ps1 后重试。"
    }
}
if (-not $SkipDatabase) {
    $localConfig = Join-Path $backendDir '.env'
    if (Test-Path -LiteralPath $localConfig) {
        $localSettings = ConvertFrom-StringData ([System.IO.File]::ReadAllText($localConfig))
        if ($localSettings.DB_MANAGED_WSL -eq 'true') { & (Join-Path $projectDir 'start-db.ps1') }
    }
}
if ($Build) {
    Push-Location $frontendDir
    try {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
    } finally { Pop-Location }
    Push-Location $backendDir
    try {
        & .\mvnw.cmd verify -B -ntp
        if ($LASTEXITCODE -ne 0) { throw '后端构建失败' }
    } finally { Pop-Location }
}
$jarPath = Join-Path $backendDir 'target\travel-java-server-0.0.1-SNAPSHOT.jar'
$vitePath = Join-Path $frontendDir 'node_modules\vite\bin\vite.js'
if (-not (Test-Path -LiteralPath $jarPath) -or -not (Test-Path -LiteralPath $vitePath)) {
    throw '缺少构建产物或依赖，请运行 .\start.ps1 -Build'
}
$started = @()
$javaExecutable = (Get-Command java.exe).Source
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    # Oracle's PATH shim can spawn a second JVM; track the actual JVM instead.
    $javaExecutable = Join-Path $env:JAVA_HOME 'bin\java.exe'
}
try {
    # Use the current Windows trust store, including public CA updates absent from an older JDK.
    $backend = Start-Process -FilePath $javaExecutable -ArgumentList @('-Djavax.net.ssl.trustStoreType=Windows-ROOT', '-jar', ('"' + $jarPath + '"')) -WorkingDirectory $backendDir -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDir 'backend.log') -RedirectStandardError (Join-Path $runtimeDir 'backend-error.log')
    $started += $backend
    $frontend = Start-Process -FilePath (Get-Command node.exe).Source -ArgumentList @(('"' + $vitePath + '"')) -WorkingDirectory $frontendDir -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDir 'frontend.log') -RedirectStandardError (Join-Path $runtimeDir 'frontend-error.log')
    $started += $frontend
    @{ backend = $backend.Id; frontend = $frontend.Id; startedAt = (Get-Date).ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtimeDir 'processes.json') -Encoding utf8
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($backend.HasExited -or $frontend.HasExited) { throw '服务意外退出，请检查 .runtime 中的日志' }
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:3200/api/travel/hello' -TimeoutSec 2
            $page = Invoke-WebRequest -Uri 'http://127.0.0.1:5173' -UseBasicParsing -TimeoutSec 2
            if ($health.success -and $page.StatusCode -eq 200) { $ready = $true; break }
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $ready) { throw '服务未按时就绪，请检查 .runtime 中的日志' }
    Write-Output '前端：http://127.0.0.1:5173'
    Write-Output '后端：http://127.0.0.1:3200/api/travel/hello'
    Write-Output '停止：运行 .\stop.ps1'
} catch {
    foreach ($process in $started) { if (-not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue } }
    throw
}
