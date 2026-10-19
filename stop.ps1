$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
$statePath = Join-Path $projectDir '.runtime\processes.json'
if (-not (Test-Path -LiteralPath $statePath)) { Write-Output '没有本项目记录的运行进程'; return }
$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
foreach ($processId in @($state.backend, $state.frontend)) {
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue
    if ($processInfo) {
        if (-not $processInfo.CommandLine.Contains($projectDir)) { throw "进程 $processId 已不属于本项目，停止操作" }
        Stop-Process -Id $processId
        Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
        Write-Output "已停止本项目进程 $processId"
    }
}
Remove-Item -LiteralPath $statePath
