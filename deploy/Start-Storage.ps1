param([string]$MinioExecutable)
. (Join-Path $PSScriptRoot 'Environment.ps1')
$root = Get-OntoRoot
$config = Import-OntoEnvironment
if (-not $MinioExecutable) { $MinioExecutable = Join-Path $root '.tools/minio/minio.exe' }
$binary = (Resolve-Path -LiteralPath $MinioExecutable).Path
foreach ($port in @(19100, 19101)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        throw "本体存储端口 $port 已占用，请核对本工程存储状态；未复用已有服务。"
    }
}
# 使用本工程随机账户和独立数据目录，不读取其他 MinIO 的配置或数据。
if (-not $config.PSObject.Properties['ONTO_STORAGE_ACCESS_KEY']) {
    $config | Add-Member -NotePropertyName ONTO_STORAGE_ACCESS_KEY -NotePropertyValue ('onto-' + [Guid]::NewGuid().ToString('N').Substring(0, 16))
    $config | Add-Member -NotePropertyName ONTO_STORAGE_SECRET_KEY -NotePropertyValue (New-OntoPassword)
    $config | Add-Member -NotePropertyName ONTO_STORAGE_ENDPOINT -NotePropertyValue 'http://127.0.0.1:19100'
    $config | Add-Member -NotePropertyName ONTO_STORAGE_BUCKET -NotePropertyValue 'ontorepo'
    $config | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root '.runtime/local-env.json') -Encoding utf8
}
$asciiRoot = Get-OntoAsciiRoot $config.ONTO_LOCAL_DRIVE
$dataDir = Join-Path $asciiRoot '.runtime/minio-data'
New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
$env:MINIO_ROOT_USER = $config.ONTO_STORAGE_ACCESS_KEY
$env:MINIO_ROOT_PASSWORD = $config.ONTO_STORAGE_SECRET_KEY
try {
    $storage = Start-Process -FilePath $binary `
        -ArgumentList @('server', $dataDir, '--address', '127.0.0.1:19100', '--console-address', '127.0.0.1:19101') `
        -WorkingDirectory $root -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $root '.runtime/storage.log') `
        -RedirectStandardError (Join-Path $root '.runtime/storage-error.log')
} finally {
    # 凭据只供刚创建的子进程继承，不留在当前终端会话中。
    Remove-Item Env:MINIO_ROOT_USER -ErrorAction SilentlyContinue
    Remove-Item Env:MINIO_ROOT_PASSWORD -ErrorAction SilentlyContinue
}
@{ pid = $storage.Id; executable = $binary; marker = $dataDir } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root '.runtime/storage-process.json') -Encoding utf8
$client = [Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(1)
$ready = $false
try {
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $storage.Refresh()
        if ($storage.HasExited) { throw '本体对象存储启动失败，详见 .runtime/storage-error.log。' }
        try {
            $response = $client.GetAsync('http://127.0.0.1:19100/minio/health/ready').GetAwaiter().GetResult()
            $ready = $response.IsSuccessStatusCode
            $response.Dispose()
            if ($ready) { break }
        } catch [Net.Http.HttpRequestException] {
            # 只在限定启动窗口内重试，不改变已有实例。
        } catch [Threading.Tasks.TaskCanceledException] {}
        Start-Sleep -Milliseconds 500
    }
} finally { $client.Dispose() }
if (-not $ready) { throw '本体对象存储尚未就绪，请查看本工程日志。' }
Write-Output '本体对象存储已就绪：127.0.0.1:19100；独立数据和凭据位于本工程 .runtime。'
