param(
    [Parameter(Mandatory = $true)][string]$HostName,
    [Parameter(Mandatory = $true)][string]$UserName,
    [Parameter(Mandatory = $true)][string]$PrivateKey,
    [Parameter(Mandatory = $true)][string]$KnownHostsFile,
    [int]$RemotePort = 18889,
    [int]$LocalPort = 8889
)
$ErrorActionPreference = 'Stop'
foreach ($file in @($PrivateKey, $KnownHostsFile)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw 'Relay credential file missing' }
    $acl = New-Object Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sidText in @('S-1-5-18', 'S-1-5-32-544')) {
        $sid = New-Object Security.Principal.SecurityIdentifier($sidText)
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'Allow')))
    }
    Set-Acl -LiteralPath $file -AclObject $acl
}
$arguments = '-N -T -o BatchMode=yes -o StrictHostKeyChecking=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 -o ServerAliveCountMax=3 -o ConnectTimeout=10 -o "UserKnownHostsFile={0}" -i "{1}" -R 127.0.0.1:{2}:127.0.0.1:{3} {4}@{5}' -f $KnownHostsFile, $PrivateKey, $RemotePort, $LocalPort, $UserName, $HostName
$action = New-ScheduledTaskAction -Execute 'C:\Windows\System32\OpenSSH\ssh.exe' -Argument $arguments
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -StartWhenAvailable -MultipleInstances IgnoreNew
$trigger = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
Register-ScheduledTask -TaskName 'MeetingNotesApp-V100-ReverseTunnel' -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Select-Object TaskName,State
Start-ScheduledTask -TaskName 'MeetingNotesApp-V100-ReverseTunnel'
