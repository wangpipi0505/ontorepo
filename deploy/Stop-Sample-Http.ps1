. (Join-Path $PSScriptRoot 'Environment.ps1')
$recordPath = Join-Path (Get-OntoRoot) '.runtime/sample-http-process.json'
if (Test-Path -LiteralPath $recordPath) {
    $record = Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $($record.pid)" -ErrorAction SilentlyContinue
    if ($process) {
        # PID重用时不能结束其他服务；可执行文件和本工程脚本标识必须同时匹配。
        if ($process.ExecutablePath -ne $record.executable -or -not $process.CommandLine.Contains($record.marker)) {
            throw '样例进程归属不匹配，拒绝停止。'
        }
        Stop-Process -Id $record.pid -ErrorAction Stop
    }
}
Write-Output '本工程HTTP验收样例已停止；其他服务保持原状态。'
