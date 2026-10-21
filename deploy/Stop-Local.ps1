param([switch]$WithDatabase)
. (Join-Path $PSScriptRoot 'Environment.ps1')
$root = Get-OntoRoot
$processFile = Join-Path $root '.runtime/processes.json'
if (Test-Path -LiteralPath $processFile) {
    $records = Get-Content -LiteralPath $processFile -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($record in $records) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId = $($record.pid)" -ErrorAction SilentlyContinue
        if (-not $process) { continue }
        # PID 可能被操作系统重用，必须同时核验可执行文件和本工程命令行标识。
        if ($process.ExecutablePath -ne $record.executable -or -not $process.CommandLine.Contains($record.marker)) {
            throw "进程 $($record.pid) 与本工程启动记录不匹配，拒绝停止。"
        }
        Stop-Process -Id $record.pid -ErrorAction Stop
    }
}
if ($WithDatabase) {
    $config = Import-OntoEnvironment
    # Get-OntoAsciiRoot 通过工程标记核验映射归属，随后使用固定子目录，不接受任意数据库路径。
    $asciiRoot = Get-OntoAsciiRoot $config.ONTO_LOCAL_DRIVE
    $dataDirectory = Join-Path $asciiRoot '.runtime/pgsql'
    Invoke-OntoCommand (Join-Path $config.ONTO_PG_BIN 'pg_ctl.exe') @('-D', $dataDirectory, '-m', 'fast', '-w', 'stop')
}
Write-Output '已停止本工程记录且归属核验通过的进程；数据文件保留。'
