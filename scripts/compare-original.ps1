param([switch]$Backup)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$originalZip=Join-Path (Split-Path -Parent $root) '旅游项目java(源码).zip'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Push-Location $root
try {
  $paths=@(& git -c core.quotepath=false ls-files --cached --others --exclude-standard)
  $paths=@($paths | Where-Object {$_ -notmatch '^docs/(原始源码对比清单.json|源码改动审查报告.md)$'})
  if($Backup){
    $destination=Join-Path $root ('.runtime/source-before-audit-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.zip')
    $backupZip=[IO.Compression.ZipFile]::Open($destination,[IO.Compression.ZipArchiveMode]::Create)
    try {foreach($path in $paths){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($backupZip,(Join-Path $root $path),$path,[IO.Compression.CompressionLevel]::Optimal)|Out-Null}}finally{$backupZip.Dispose()}
    Write-Output "源码备份：$destination（不含.env、运行凭据、数据库、依赖和构建产物）"
  }
  function Category([string]$path){
    if($path -match '^docs/|(^|/)README|\.md$'){return '文档'}
    if($path -match '/(test|tests)/|^eval/'){return '测试与评估'}
    if($path -match '^travel-java-server/src/main/'){return '后端业务与配置'}
    if($path -match '^travel-h5-java/src/'){return '前端业务'}
    if($path -match '^scripts/|^deploy/|^\.github/|\.ps1$|compose|Docker'){return '运行部署与辅助脚本'}
    return '依赖、构建与其他资源'
  }
  $baseline=@{};$zip=[IO.Compression.ZipFile]::OpenRead($originalZip)
  try{foreach($entry in $zip.Entries){
    $path=$entry.FullName.Replace('\','/')
    if($entry.Name -eq '' -or $path -match '(^|/)(target|node_modules|dist|\.git|\.idea|\.vscode)/|(^|/)\.DS_Store$'){continue}
    $buffer=[IO.MemoryStream]::new();$stream=$entry.Open();try{$stream.CopyTo($buffer)}finally{$stream.Dispose()}
    $bytes=$buffer.ToArray();$buffer.Dispose();$baseline[$path]=$bytes
  }}finally{$zip.Dispose()}
  $records=@();$all=@($baseline.Keys)+$paths|Sort-Object -Unique
  foreach($path in $all){
    $had=$baseline.ContainsKey($path);$exists=($paths -contains $path) -or ($had -and (Test-Path -LiteralPath (Join-Path $root $path) -PathType Leaf))
    $before=if($had){$baseline[$path]}else{$null};$after=if($exists){[IO.File]::ReadAllBytes((Join-Path $root $path))}else{$null}
    $originalHash=if($had){[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($before))}else{$null}
    $currentHash=if($exists){[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($after))}else{$null}
    $status=if(!$had){'新增'}elseif(!$exists){'删除'}elseif($originalHash -eq $currentHash){'未变'}else{'修改'}
    $records+=[PSCustomObject]@{path=$path;status=$status;category=(Category $path);originalBytes=if($had){$before.Length}else{0};currentBytes=if($exists){$after.Length}else{0};originalSha256=$originalHash;currentSha256=$currentHash}
  }
  $counts=[ordered]@{};foreach($status in @('新增','修改','删除','未变')){$counts[$status]=@($records|Where-Object status -eq $status).Count}
  $result=[ordered]@{at=(Get-Date).ToString('o');originalZip=(Split-Path -Leaf $originalZip);originalZipSha256=(Get-FileHash -LiteralPath $originalZip -Algorithm SHA256).Hash;excluded='target/node_modules/dist/.git/.idea/.vscode/.DS_Store；当前Git忽略项；本报告自身。只报告路径、大小、哈希，不输出原始配置密钥。';summary=$counts;files=$records}
  [IO.File]::WriteAllText((Join-Path $root 'docs/原始源码对比清单.json'),($result|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
  $counts|ConvertTo-Json -Compress
  $records|Where-Object {$_.category -in @('后端业务与配置','前端业务') -and $_.status -ne '未变'}|Select-Object status,path|Format-Table -AutoSize
}finally{Pop-Location}
