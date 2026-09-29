param(
  [switch]$CreateAccount,
  [string]$PaperRoot,
  [string]$ModelId
)
$ErrorActionPreference = 'Stop'
$account = 'SkyIslandSvc'
if ($CreateAccount) {
  $admin = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
  if (-not $admin.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw '创建账号需要管理员权限' }
  if (-not (Get-LocalUser -Name $account -ErrorAction SilentlyContinue)) {
    $password = Read-Host '设置 SkyIslandSvc 专用账号密码' -AsSecureString
    New-LocalUser -Name $account -Password $password -Description 'SkyIslandSystem dedicated OpenClaw account' | Out-Null
  }
  Write-Host "账号已准备。请登录 $account，再运行本脚本 -PaperRoot <目录> -ModelId <提供商/模型ID>"
  exit
}
if ([Security.Principal.WindowsIdentity]::GetCurrent().Name.Split('\')[-1] -ne $account) { throw "请在独立账号 $account 下运行；管理员先使用 -CreateAccount" }
if (-not $PaperRoot -or -not $ModelId) { throw '用法: windows.ps1 -PaperRoot <Paper目录> -ModelId <提供商/模型ID>' }
if (-not (Test-Path (Join-Path $PaperRoot 'plugins') -PathType Container)) { throw 'Paper plugins 目录不存在' }
if (-not (Test-Path (Join-Path $PaperRoot 'eula.txt'))) { throw 'Paper EULA 文件不存在' }
if (-not (Select-String -Path (Join-Path $PaperRoot 'eula.txt') -Pattern '^eula=true$' -Quiet)) { throw 'EULA 未由服务器所有者接受' }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (@(Get-ChildItem -LiteralPath (Join-Path $PaperRoot 'plugins') -Filter 'SkyIslandSystem-*.jar' -File).Count -eq 0) { throw '请先将天空岛体系 Release JAR 放入 Paper/plugins' }
$javaVersion = (& java -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "([0-9]+)') { throw '需要 Java 17+' }
if ([int]$Matches[1] -lt 17) { throw 'Java 版本不受支持' }
$paperJar = Join-Path $PaperRoot 'Paper-1.20.1.jar'
if (-not (Test-Path $paperJar)) { throw '找不到 Paper-1.20.1.jar' }
Add-Type -AssemblyName System.IO.Compression
$zip = [IO.Compression.ZipFile]::OpenRead($paperJar)
try {
  $reader = [IO.StreamReader]::new($zip.GetEntry('version.json').Open())
  try { $paperVersion = ($reader.ReadToEnd() | ConvertFrom-Json).id } finally { $reader.Dispose() }
  $reader = [IO.StreamReader]::new($zip.GetEntry('META-INF/MANIFEST.MF').Open())
  try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
} finally { $zip.Dispose() }
if ($paperVersion -ne '1.20.1' -or $manifest -notmatch 'io\.papermc\.paperclip\.Main') { throw 'Paper 版本检查失败' }
$driveRoot = [IO.Path]::GetPathRoot((Resolve-Path $PaperRoot).Path)
if ([IO.DriveInfo]::new($driveRoot).AvailableFreeSpace -lt 2GB) { throw 'Paper 分区可用空间不足 2 GiB' }
$nodeVersion = (& node -p 'process.versions.node')
if ($LASTEXITCODE -ne 0) { throw '请先安装 Node 24.16+ 或 26.1+' }
$v = [version]$nodeVersion
if (-not (($v.Major -eq 24 -and $v.Minor -ge 16) -or ($v.Major -eq 26 -and $v.Minor -ge 1) -or $v.Major -gt 26)) { throw 'Node 版本不受支持' }
if (-not (Get-Command openclaw -ErrorAction SilentlyContinue)) { throw '请在专用账号下安装 OpenClaw (npm install -g openclaw)，并配置模型凭证' }
$state = Join-Path $env:USERPROFILE '.openclaw-skyisland'
New-Item -ItemType Directory -Path $state -Force | Out-Null
$env:OPENCLAW_STATE_DIR = $state
$env:OPENCLAW_CONFIG_PATH = Join-Path $state 'openclaw.json'
if (-not (Test-Path $env:OPENCLAW_CONFIG_PATH)) { & openclaw setup --baseline; if ($LASTEXITCODE -ne 0) { throw 'OpenClaw baseline 初始化失败' } }
$roles = @('phanes','ronova','naberius','istaroth','asmoday')
$entries = @{}
foreach ($role in $roles) {
  $workspace = Join-Path $state "workspaces\$role"
  New-Item -ItemType Directory -Path $workspace -Force | Out-Null
  foreach ($name in @('AGENTS.md', 'SOUL.md')) {
    $source = Join-Path $repo "personas\$role\$name"
    $target = Join-Path $workspace $name
    if ((Test-Path -LiteralPath $target) -and
        (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash) {
      $stamp = Get-Date -Format 'yyyyMMddTHHmmssfff'
      Copy-Item -LiteralPath $target -Destination "$target.$stamp.bak"
    }
    Copy-Item -LiteralPath $source -Destination $target -Force
  }
  if (-not (Test-Path (Join-Path $workspace 'MEMORY.md'))) { New-Item -ItemType File -Path (Join-Path $workspace 'MEMORY.md') | Out-Null }
  $entries[$role] = @{ workspace = $workspace; agentDir = (Join-Path $state "agents\$role\agent"); identity = @{ name = $role }; default = ($role -eq 'phanes') }
}
$tokenFile = Join-Path $state 'gateway.token'
if (-not (Test-Path $tokenFile)) {
  $bytes = New-Object byte[] 32
  [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
  [IO.File]::WriteAllText($tokenFile, [Convert]::ToHexString($bytes).ToLowerInvariant())
}
$token = [IO.File]::ReadAllText($tokenFile).Trim()
$patch = @{
  gateway = @{ mode = 'local'; port = 19789; bind = 'loopback'; auth = @{ mode = 'token'; token = $token }; http = @{ endpoints = @{ chatCompletions = @{ enabled = $true } } } }
  tools = @{ profile = 'minimal'; sessions = @{ visibility = 'self' }; agentToAgent = @{ enabled = $false }; fs = @{ workspaceOnly = $true }; deny = @('exec','process','read','write','edit','apply_patch','browser','gateway','sessions_list','sessions_history','sessions_search','sessions_send','sessions_spawn') }
  agents = @{ defaults = @{ model = @{ primary = $ModelId } }; entries = $entries }
}
$patchFile = Join-Path $state 'skyisland.patch.json'
[IO.File]::WriteAllText($patchFile, ($patch | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
& openclaw config patch --file $patchFile --replace-path agents.entries --dry-run
if ($LASTEXITCODE -ne 0) { throw '配置预检失败；未应用配置' }
& openclaw config patch --file $patchFile --replace-path agents.entries
if ($LASTEXITCODE -ne 0) { throw '配置写入失败' }
& openclaw config validate
if ($LASTEXITCODE -ne 0) { throw '配置验证失败' }
if ((& openclaw config get gateway.port) -ne '19789' -or
    (& openclaw config get gateway.bind) -ne 'loopback' -or
    (& openclaw config get tools.profile) -ne 'minimal' -or
    (& openclaw config get tools.agentToAgent.enabled) -ne 'false') { throw 'Gateway 或工具隔离配置未生效' }
$agentNames = (& openclaw config get agents.entries --json | ConvertFrom-Json).PSObject.Properties.Name
if (@(Compare-Object $agentNames @('phanes','ronova','naberius','istaroth','asmoday')).Count -ne 0) { throw 'Agent 数量或身份不正确' }
& openclaw hooks enable session-memory
if ($LASTEXITCODE -ne 0) { throw '独立记忆 hook 启用失败' }
& openclaw agents list
if ($LASTEXITCODE -ne 0) { throw 'Agent 列表检查失败' }
$launcher = @'
$ErrorActionPreference = 'Stop'
$env:OPENCLAW_STATE_DIR = $PSScriptRoot
$env:OPENCLAW_CONFIG_PATH = Join-Path $PSScriptRoot 'openclaw.json'
& openclaw gateway run --port 19789
'@
[IO.File]::WriteAllText((Join-Path $state 'start-skyisland-gateway.ps1'), $launcher, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $state 'plugin-secrets.yml'), "gateway-token: `"$token`"`n", [Text.UTF8Encoding]::new($false))
& icacls $state /inheritance:r /grant:r "${account}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw '私有状态目录 ACL 设置失败' }
Write-Host "配置已生成于 $state。由 Paper 管理员安装 JAR，并安全复制 plugin-secrets.yml 到 Paper/plugins/SkyIslandSystem/secrets.yml。"
Write-Host '由专用账号运行 start-skyisland-gateway.ps1，再做真实模型与隔离验收；脚本未宣称已完成安装。'
