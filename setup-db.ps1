$ErrorActionPreference = 'Stop'
$backendDir = Join-Path $PSScriptRoot 'travel-java-server'
$configPath = Join-Path $backendDir '.env'
if (-not (Test-Path -LiteralPath $configPath)) { Copy-Item -LiteralPath (Join-Path $backendDir '.env.example') -Destination $configPath }
& wsl.exe -d Ubuntu-24.04 -u root -- bash -lc 'if ! command -v mysqld >/dev/null; then export DEBIAN_FRONTEND=noninteractive; apt-get update -qq && apt-get install -y mysql-server; fi'
if ($LASTEXITCODE -ne 0) { throw 'MySQL 安装失败' }
& (Join-Path $PSScriptRoot 'start-db.ps1')
$existing = [System.IO.File]::ReadAllText($configPath)
$settings = ConvertFrom-StringData $existing
$dbPassword = $settings.DB_PASSWORD
if (-not $dbPassword) { $dbPassword = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant() }
if ($dbPassword -notmatch '^[a-f0-9]{48}$') { throw '已有外部数据库密码，请按 README 手动初始化数据库，避免覆盖现有配置' }
$sql = "CREATE DATABASE IF NOT EXISTS travel_ai CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE DATABASE IF NOT EXISTS travel_ai_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS 'travel_ai_app'@'%' IDENTIFIED BY '$dbPassword'; GRANT ALL PRIVILEGES ON travel_ai.* TO 'travel_ai_app'@'%'; GRANT ALL PRIVILEGES ON travel_ai_test.* TO 'travel_ai_app'@'%';"
$sql | & wsl.exe -d Ubuntu-24.04 -u root -- mysql -u root
if ($LASTEXITCODE -ne 0) { throw '数据库初始化失败' }
$values = @{
 DB_URL='jdbc:mysql://127.0.0.1:3306/travel_ai?serverTimezone=UTC&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false'
 DB_TEST_URL='jdbc:mysql://127.0.0.1:3306/travel_ai_test?serverTimezone=UTC&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false'
 DB_USERNAME='travel_ai_app'
 DB_PASSWORD=$dbPassword
 DB_MANAGED_WSL='true'
}
foreach ($name in $values.Keys) {
 $line = $name + '=' + $values[$name]
 if ($existing -match ('(?m)^' + $name + '=')) { $existing = [regex]::Replace($existing,('(?m)^'+$name+'=.*$'),$line) }
 else { $existing += "`n" + $line }
}
[System.IO.File]::WriteAllText($configPath,$existing+"`n",[System.Text.UTF8Encoding]::new($false))
Write-Output 'MySQL 开发库、测试库和应用账号已就绪；凭据写入忽略的后端 .env'
