param([ValidateSet('up','status','down','cert')][string]$Action='up',[switch]$Integration,[switch]$Build)
$ErrorActionPreference='Stop'
$projectDir=$PSScriptRoot
$envFile=Join-Path $projectDir $(if($Integration){'.runtime/integration.env'}else{'.runtime/compose.env'})
New-Item -ItemType Directory -Path (Join-Path $projectDir '.runtime') -Force | Out-Null
if(!(Test-Path -LiteralPath $envFile)){
  $appSettings=@{};if(!$Integration){$appSettings=ConvertFrom-StringData ([IO.File]::ReadAllText((Join-Path $projectDir 'travel-java-server/.env')));if(!$appSettings.LLM_API_KEY){throw '请先在后端.env配置模型Key'}}
  $databasePassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
  $rootPassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
  $httpPort=if($Integration){9080}else{8080};$httpsPort=if($Integration){9443}else{8443}
  $modelKey=if($Integration){'fixture-only-key'}else{$appSettings.LLM_API_KEY}
  [IO.File]::WriteAllLines($envFile,@("DB_PASSWORD=$databasePassword","MYSQL_ROOT_PASSWORD=$rootPassword","LLM_API_KEY=$modelKey",'LLM_BASE_URL=https://api.siliconflow.cn/v1','LLM_MODEL=Qwen/Qwen3.5-35B-A3B',"HTTP_PORT=$httpPort","HTTPS_PORT=$httpsPort"),[Text.UTF8Encoding]::new($false))
}
$native=$false
try{& docker.exe info --format '{{.ServerVersion}}' 2>$null | Out-Null;$native=$LASTEXITCODE -eq 0}catch{}
$projectName=if($Integration){'travel-ai-integration'}else{'travel-ai'}
if($native){$arguments=@('compose','--project-directory',$projectDir,'--env-file',$envFile,'-p',$projectName,'-f',(Join-Path $projectDir 'compose.yaml'));if($Integration){$arguments+=@('-f',(Join-Path $projectDir 'compose.test.yaml'))}}
else{
  $linuxRoot=(wsl.exe -d Ubuntu-24.04 -- wslpath -a $projectDir.Replace('\','/')).Trim();$linuxEnv=(wsl.exe -d Ubuntu-24.04 -- wslpath -a $envFile.Replace('\','/')).Trim()
  & wsl.exe -d Ubuntu-24.04 -u root -- systemctl start docker
  if($LASTEXITCODE -ne 0){throw '需要可运行的Docker Engine；详见部署说明'}
  $arguments=@('-d','Ubuntu-24.04','-u','root','--','/usr/bin/docker','compose','--project-directory',$linuxRoot,'--env-file',$linuxEnv,'-p',$projectName,'-f',($linuxRoot+'/compose.yaml'));if($Integration){$arguments+=@('-f',($linuxRoot+'/compose.test.yaml'))}
}
function Invoke-Compose([string[]]$operation){if($native){& docker.exe @arguments @operation}else{& wsl.exe @arguments @operation};if($LASTEXITCODE -ne 0){throw "Compose $Action 失败"}}
switch($Action){
  up {$operation=@('up','-d','--wait','--wait-timeout','240');if($Build){$operation+='--build'};Invoke-Compose $operation}
  status {Invoke-Compose @('ps')}
  down {Invoke-Compose @('down')}
  cert {$destination=Join-Path $projectDir $(if($Integration){'.runtime/integration-root.crt'}else{'.runtime/localhost-root.crt'});if($native){$target=$destination}else{$target=(wsl.exe -d Ubuntu-24.04 -- wslpath -a $destination.Replace('\','/')).Trim()};Invoke-Compose -operation @('cp','web:/data/caddy/pki/authorities/local/root.crt',$target);Write-Output "公开CA证书：$destination（没有修改系统信任库）"}
}
