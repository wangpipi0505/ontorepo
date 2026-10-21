. (Join-Path $PSScriptRoot 'Environment.ps1')
$recordPath = Join-Path (Get-OntoRoot) '.runtime/storage-process.json'
if (-not (Test-Path -LiteralPath $recordPath)) { Write-Output '本工程没有对象存储进程记录。'; return }
$record = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
$owned = Get-CimInstance Win32_Process -Filter ("ProcessId=" + [int]$record.pid)
if ($owned) {
    # 防止 Windows PID 被复用后误停相邻服务，路径和专属数据目录必须同时匹配。
    if ($owned.ExecutablePath -ne $record.executable -or
        $owned.CommandLine -notlike ('*' + $record.marker + '*')) {
        throw '进程归属不符，拒绝停止。'
    }
    Stop-Process -Id ([int]$record.pid) -ErrorAction Stop
}
Write-Output '本工程对象存储已停止，所有数据保留。'
