$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sourceScript = Join-Path $repoRoot 'deploy\windows.ps1'
$scriptCopy = Join-Path $PSScriptRoot ('.windows-deploy-smoke-copy-' + [Guid]::NewGuid().ToString('N') + '.ps1')
$runRoot = Join-Path $PSScriptRoot ('windows-deploy-smoke-run-' + [Guid]::NewGuid().ToString('N'))
$mockBin = Join-Path $runRoot 'bin'
$paperRoot = Join-Path $runRoot 'paper'
$profileRoot = Join-Path $runRoot 'profile'
$mockLogPath = Join-Path $runRoot 'host-mock.log'
$expectedRoles = @('phanes', 'ronova', 'naberius', 'istaroth', 'asmoday')

function Assert-Smoke([bool]$Condition, [string]$Message) {
  if (-not $Condition) { throw $Message }
}

try {
  New-Item -ItemType Directory -Path $mockBin, (Join-Path $paperRoot 'plugins'), $profileRoot -Force | Out-Null
  Set-Content -LiteralPath (Join-Path $paperRoot 'eula.txt') -Value 'eula=true' -Encoding ASCII
  Set-Content -LiteralPath (Join-Path $paperRoot 'plugins\SkyIslandSystem-0.7.0-beta.2.jar') -Value 'fixture' -Encoding ASCII

  Add-Type -AssemblyName System.IO.Compression
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $paperJar = Join-Path $paperRoot 'Paper-1.20.1.jar'
  $archive = [IO.Compression.ZipFile]::Open($paperJar, [IO.Compression.ZipArchiveMode]::Create)
  try {
    $entry = $archive.CreateEntry('version.json')
    $writer = New-Object IO.StreamWriter($entry.Open())
    try { $writer.Write('{"id":"1.20.1"}') } finally { $writer.Dispose() }
    $entry = $archive.CreateEntry('META-INF/MANIFEST.MF')
    $writer = New-Object IO.StreamWriter($entry.Open())
    try { $writer.Write("Main-Class: io.papermc.paperclip.Main`r`n") } finally { $writer.Dispose() }
  } finally { $archive.Dispose() }

  $sourceBytes = [IO.File]::ReadAllBytes($sourceScript)
  Assert-Smoke ($sourceBytes[0] -eq 239 -and $sourceBytes[1] -eq 187 -and $sourceBytes[2] -eq 191) 'Deployment source needs UTF-8 BOM for Windows PowerShell 5.1 Chinese text.'
  $scriptLines = [IO.File]::ReadAllLines($sourceScript)
  $accountGateCount = 0
  for ($i = 0; $i -lt $scriptLines.Length; $i++) {
    if ($scriptLines[$i].Contains('WindowsIdentity]::GetCurrent().Name.Split') -and $scriptLines[$i].Contains('-ne $account')) {
      $scriptLines[$i] = '# isolated smoke only: bypass current-account identity gate'
      $accountGateCount++
    }
  }
  Assert-Smoke ($accountGateCount -eq 1) 'Could not identify exactly one current-account gate to bypass in the temporary copy.'
  [IO.File]::WriteAllLines($scriptCopy, $scriptLines, (New-Object Text.UTF8Encoding($true)))

  $javaShim = @'
@echo off
echo openjdk version "17.0.1" 1>&2
exit /b 0
'@
  [IO.File]::WriteAllText((Join-Path $mockBin 'java.cmd'), $javaShim, [Text.Encoding]::ASCII)
  $nodeShim = @'
@echo off
echo 24.16.0
exit /b 0
'@
  [IO.File]::WriteAllText((Join-Path $mockBin 'node.cmd'), $nodeShim, [Text.Encoding]::ASCII)
  $aclShim = @'
@echo off
echo icacls-mock>>"%SMOKE_LOG%"
exit /b 0
'@
  [IO.File]::WriteAllText((Join-Path $mockBin 'icacls.cmd'), $aclShim, [Text.Encoding]::ASCII)
  $openClawShim = @'
@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0openclaw-mock.ps1" %*
exit /b %errorlevel%
'@
  [IO.File]::WriteAllText((Join-Path $mockBin 'openclaw.cmd'), $openClawShim, [Text.Encoding]::ASCII)
  $openClawMock = @'
param([Parameter(ValueFromRemainingArguments=$true)][string[]]$CliArgs)
$ErrorActionPreference = 'Stop'
Add-Content -LiteralPath $env:SMOKE_LOG -Value ('openclaw ' + ($CliArgs -join ' '))
$configPath = $env:OPENCLAW_CONFIG_PATH
if ($CliArgs[0] -eq 'config' -and $CliArgs[1] -eq 'patch') {
  $fileIndex = [Array]::IndexOf($CliArgs, '--file')
  if ($fileIndex -lt 0) { exit 2 }
  $patchPath = $CliArgs[$fileIndex + 1]
  $patchText = [IO.File]::ReadAllText($patchPath)
  $null = $patchText | ConvertFrom-Json
  if ($CliArgs -notcontains '--dry-run') { [IO.File]::WriteAllText($configPath, $patchText, (New-Object Text.UTF8Encoding($false))) }
  exit 0
}
if ($CliArgs[0] -eq 'config' -and $CliArgs[1] -eq 'validate') {
  $null = [IO.File]::ReadAllText($configPath) | ConvertFrom-Json
  exit 0
}
if ($CliArgs[0] -eq 'config' -and $CliArgs[1] -eq 'get') {
  $config = [IO.File]::ReadAllText($configPath) | ConvertFrom-Json
  switch ($CliArgs[2]) {
    'gateway.port' { [string]$config.gateway.port; exit 0 }
    'gateway.bind' { [string]$config.gateway.bind; exit 0 }
    'tools.profile' { [string]$config.tools.profile; exit 0 }
    'tools.agentToAgent.enabled' { [string]$config.tools.agentToAgent.enabled; exit 0 }
    'agents.entries' { $config.agents.entries | ConvertTo-Json -Depth 20 -Compress; exit 0 }
  }
  exit 3
}
if ($CliArgs[0] -eq 'hooks' -and $CliArgs[1] -eq 'enable') { exit 0 }
if ($CliArgs[0] -eq 'agents' -and $CliArgs[1] -eq 'list') { 'mock agents listed'; exit 0 }
exit 4
'@
  [IO.File]::WriteAllText((Join-Path $mockBin 'openclaw-mock.ps1'), $openClawMock, (New-Object Text.UTF8Encoding($false)))

  $stateRoot = Join-Path $profileRoot '.openclaw-skyisland'
  New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
  [IO.File]::WriteAllText((Join-Path $stateRoot 'openclaw.json'), '{}', [Text.Encoding]::ASCII)
  foreach ($role in $expectedRoles) {
    $workspace = Join-Path $stateRoot ('workspaces\' + $role)
    New-Item -ItemType Directory -Path $workspace -Force | Out-Null
    foreach ($name in @('AGENTS.md','SOUL.md','TOOLS.md')) { [IO.File]::WriteAllText((Join-Path $workspace $name), ('custom ' + $name), [Text.Encoding]::ASCII) }
    [IO.File]::WriteAllText((Join-Path $workspace 'MEMORY.md'), "PRIVATE MEMORY PRESERVED`n", [Text.Encoding]::ASCII)
  }

  $oldPath = $env:Path
  $oldProfile = $env:USERPROFILE
  $oldLocalAppData = $env:LOCALAPPDATA
  $oldAppData = $env:APPDATA
  $oldSmokeLog = $env:SMOKE_LOG
  $childStdoutPath = Join-Path $runRoot 'child.stdout.txt'
  $childStderrPath = Join-Path $runRoot 'child.stderr.txt'
  $env:Path = $mockBin + ';' + $oldPath
  $env:USERPROFILE = $profileRoot
  $env:LOCALAPPDATA = Join-Path $profileRoot "AppData\Local"
  $env:APPDATA = Join-Path $profileRoot "AppData\Roaming"
  New-Item -ItemType Directory -Path $env:LOCALAPPDATA, $env:APPDATA -Force | Out-Null
  $env:SMOKE_LOG = $mockLogPath
  try {
    foreach ($pass in 1..2) {
    $childProcess = Start-Process -FilePath 'powershell.exe' -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -File "' + $scriptCopy + '" -PaperRoot "' + $paperRoot + '" -ModelId "smoke/provider-model"') -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput $childStdoutPath -RedirectStandardError $childStderrPath
    $childExit = $childProcess.ExitCode
    if ($childExit -ne 0) { break }
    }
  } finally {
    $env:Path = $oldPath
    $env:USERPROFILE = $oldProfile
    $env:LOCALAPPDATA = $oldLocalAppData
    $env:APPDATA = $oldAppData
    if ($null -eq $oldSmokeLog) { Remove-Item Env:\SMOKE_LOG -ErrorAction SilentlyContinue } else { $env:SMOKE_LOG = $oldSmokeLog }
  }

  $generatedState = Join-Path $profileRoot '.openclaw-skyisland'
  $patchPath = Join-Path $generatedState 'skyisland.patch.json'
  $configPath = Join-Path $generatedState 'openclaw.json'
  $tokenPath = Join-Path $generatedState 'gateway.token'
  $secretPath = Join-Path $generatedState 'plugin-secrets.yml'
  $launcherPath = Join-Path $generatedState 'start-skyisland-gateway.ps1'
  if ($childExit -ne 0) {
    $failureDetail = ''
    if (Test-Path -LiteralPath $childStdoutPath) { $failureDetail += [IO.File]::ReadAllText($childStdoutPath) }
    if (Test-Path -LiteralPath $childStderrPath) { $failureDetail += [IO.File]::ReadAllText($childStderrPath) }
    if (Test-Path -LiteralPath $tokenPath) { $failureDetail = $failureDetail.Replace([IO.File]::ReadAllText($tokenPath).Trim(), '[redacted]') }
    throw ('Deployment copy exited ' + $childExit + ': ' + $failureDetail)
  }
  foreach ($path in @($patchPath, $configPath, $tokenPath, $secretPath, $launcherPath)) { Assert-Smoke (Test-Path -LiteralPath $path) ('Expected generated file is missing: ' + [IO.Path]::GetFileName($path)) }

  $patch = [IO.File]::ReadAllText($patchPath) | ConvertFrom-Json
  $entries = $patch.agents.entries
  $actualRoles = @($entries.PSObject.Properties.Name | Sort-Object)
  $wantedRoles = @($expectedRoles | Sort-Object)
  $token = [IO.File]::ReadAllText($tokenPath).Trim()
  $secret = [IO.File]::ReadAllText($secretPath).Trim()
  Assert-Smoke ($patch.gateway.port -eq 19789 -and $patch.gateway.bind -eq 'loopback') 'Gateway port or bind differs from expected isolated settings.'
  Assert-Smoke ($patch.tools.profile -eq 'minimal' -and $patch.tools.agentToAgent.enabled -eq $false) 'Tool isolation settings were not generated.'
  Assert-Smoke (($actualRoles -join ',') -eq ($wantedRoles -join ',')) 'Generated agent names differ from the five expected roles.'
  Assert-Smoke ($patch.agents.defaults.model.primary -eq 'smoke/provider-model') 'Configured model identifier was not written.'
  Assert-Smoke ($token -match '^[0-9a-f]{64}$' -and $secret -eq ('gateway-token: "' + $token + '"')) 'Generated token files are missing or inconsistent.'
  Assert-Smoke ([IO.File]::ReadAllText($launcherPath).Contains('gateway run --port 19789')) 'Gateway launcher does not target the expected port.'
  $mockLog = [IO.File]::ReadAllText($mockLogPath)
  Assert-Smoke ($mockLog.Contains('openclaw config patch') -and $mockLog.Contains('openclaw config validate') -and $mockLog.Contains('icacls-mock')) 'Expected OpenClaw/ACL shims were not used.'

  $backups = @(Get-ChildItem -LiteralPath (Join-Path $stateRoot 'workspaces') -Filter '*.bak' -Recurse)
  Assert-Smoke ($backups.Count -eq 15) 'Repeated sync must preserve exactly the 15 changed original files.'
  foreach ($role in $expectedRoles) {
    $workspace = Join-Path $stateRoot ('workspaces\' + $role)
    Assert-Smoke ([IO.File]::ReadAllText((Join-Path $workspace 'MEMORY.md')) -eq "PRIVATE MEMORY PRESERVED`n") 'Private role memory changed during sync.'
    foreach ($name in @('AGENTS.md','SOUL.md','TOOLS.md')) {
      $source = if ($name -eq 'TOOLS.md') { Join-Path $repoRoot ('personas\' + $name) } else { Join-Path $repoRoot ('personas\' + $role + '\' + $name) }
      Assert-Smoke ((Get-FileHash -LiteralPath (Join-Path $workspace $name)).Hash -eq (Get-FileHash -LiteralPath $source).Hash) 'Synced document differs from source.'
    }
  }
  Write-Output 'PASS: Actual persona copy repeated; 15 backups and five memories preserved.'
  Write-Output 'PASS: Windows PowerShell 5.1 deployment config-generation smoke.'
  Write-Output 'PASS: Paper checks, five-agent patch, isolation settings, token files, launcher, and mock host commands.'
} finally {
  Remove-Item -LiteralPath $scriptCopy -Force -ErrorAction SilentlyContinue
  if ([IO.Path]::GetFullPath($runRoot).StartsWith([IO.Path]::GetFullPath($PSScriptRoot) + [IO.Path]::DirectorySeparatorChar)) { Remove-Item -LiteralPath $runRoot -Recurse -Force -ErrorAction SilentlyContinue }
}
