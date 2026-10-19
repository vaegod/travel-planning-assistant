$ErrorActionPreference = 'Stop'
$runtimeDir = Join-Path $PSScriptRoot '.runtime'
New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
$statePath = Join-Path $runtimeDir 'mysql-wsl-process.json'
$keeper = $null
if (Test-Path -LiteralPath $statePath) {
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $candidate = Get-CimInstance Win32_Process -Filter "ProcessId = $($state.processId)" -ErrorAction SilentlyContinue
    if ($candidate -and $candidate.CommandLine.Contains('Ubuntu-24.04') -and $candidate.CommandLine.Contains('sleep infinity')) { $keeper = $candidate }
}
if (-not $keeper) {
    # systemd services alone do not keep a WSL instance alive.
    $process = Start-Process -FilePath 'wsl.exe' -ArgumentList @('-d','Ubuntu-24.04','-u','root','--','sleep','infinity') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDir 'mysql-wsl.log') -RedirectStandardError (Join-Path $runtimeDir 'mysql-wsl-error.log')
    @{ processId = $process.Id; distro = 'Ubuntu-24.04' } | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
}
& wsl.exe -d Ubuntu-24.04 -u root -- service mysql start
if ($LASTEXITCODE -ne 0) { throw 'WSL MySQL 启动失败' }
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    $connection = [System.Net.Sockets.TcpClient]::new()
    try {
        if ($connection.ConnectAsync('127.0.0.1',3306).Wait(500)) { Write-Output 'MySQL 就绪：127.0.0.1:3306（WSL Ubuntu-24.04）'; return }
    } catch {} finally { $connection.Dispose() }
    Start-Sleep -Milliseconds 500
}
throw 'MySQL 未就绪，请检查 WSL 中的 mysql 服务'
