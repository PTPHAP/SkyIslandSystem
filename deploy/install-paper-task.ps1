param(
    [Parameter(Mandatory=$true)][string]$PaperRoot,
    [Parameter(Mandatory=$true)][string]$ServiceUser
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $PaperRoot).Path
$launcher = Join-Path $root 'start-server.bat'
if (-not (Test-Path -LiteralPath $launcher)) { throw '先把 deploy/start-paper-windows.bat 复制为 Paper 根目录的 start-server.bat' }
if (-not (Test-Path -LiteralPath (Join-Path $root 'Paper-1.20.1.jar'))) { throw '找不到 Paper-1.20.1.jar' }
$credential = Get-Credential -UserName $ServiceUser -Message '输入专用 Paper 运行账号密码；仅供本机计划任务使用'
if ($null -eq $credential) { throw '未提供运行账号凭证' }
$action = New-ScheduledTaskAction -Execute $env:ComSpec -Argument "/d /c `"`"$launcher`" --service`"" -WorkingDirectory $root
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit (New-TimeSpan -Seconds 0) -StartWhenAvailable
Register-ScheduledTask -TaskName 'SkyIslandPaper' -Action $action -Trigger $trigger -Settings $settings `
    -User $credential.UserName -Password $credential.GetNetworkCredential().Password -RunLevel Limited -Force | Out-Null
Write-Host '已建立 SkyIslandPaper 开机任务；请用 Start-ScheduledTask -TaskName SkyIslandPaper 测试，并检查任务历史与 Paper 日志。'
