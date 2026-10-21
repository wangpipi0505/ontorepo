param([string]$JavaHome, [switch]$Build)
. (Join-Path $PSScriptRoot 'Environment.ps1')
$root = Get-OntoRoot
$config = Import-OntoEnvironment
Set-OntoJava $JavaHome
Set-OntoBuildLimits
$jar = Join-Path $root 'onto-designer-api/target/onto-designer-api-0.1.0-SNAPSHOT.jar'
$vite = Join-Path $root 'web/apps/onto-admin/node_modules/vite/bin/vite.js'
if ($Build) {
    Push-Location $root
    try {
        Invoke-OntoCommand 'mvn.cmd' @('-B', '-DskipTests', 'package')
        Set-Location (Join-Path $root 'web')
        Invoke-OntoCommand 'pnpm.cmd' @('install', '--frozen-lockfile')
        Invoke-OntoCommand 'pnpm.cmd' @('build')
    } finally { Pop-Location }
}
if (-not (Test-Path -LiteralPath $jar)) { throw '后端制品不存在，请先运行 deploy/Test-Local.ps1 或使用 -Build。' }
if (-not (Test-Path -LiteralPath $vite)) { throw '前端依赖未安装，请在 web 目录运行 pnpm install。' }
foreach ($port in @(18182, 15184)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        throw "端口 $port 已占用；未重用或终止已有进程。请先核对并停止本工程上次启动的服务。"
    }
}
$runtime = Join-Path $root '.runtime'
$asciiRoot = Get-OntoAsciiRoot $config.ONTO_LOCAL_DRIVE
$javaTemp = Join-Path $asciiRoot '.runtime/tmp'
New-Item -ItemType Directory -Path $javaTemp -Force | Out-Null
# 仅使用固定参数和经过解析的工程路径；进程信息保存后用于安全停止，禁止按进程名批量结束 Java/Node。
# JDK 在 Windows 上建立 NIO 管道也使用临时路径；固定短 ASCII 路径避免 Unix socket 路径编码/长度错误。
$backend = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin/java.exe') `
    -ArgumentList @('-Xmx768m', ("-Djava.io.tmpdir=$javaTemp"), ("-Djdk.net.unixdomain.tmpdir=$javaTemp"), '-jar', ('"' + $jar + '"')) -WorkingDirectory $root -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $runtime 'backend.log') -RedirectStandardError (Join-Path $runtime 'backend-error.log')
$frontend = Start-Process -FilePath (Get-Command node.exe).Source `
    -ArgumentList @(('"' + $vite + '"'), '--host', '127.0.0.1', '--port', '15184', '--strictPort') `
    -WorkingDirectory (Join-Path $root 'web/apps/onto-admin') -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $runtime 'frontend.log') -RedirectStandardError (Join-Path $runtime 'frontend-error.log')
@(
    @{ pid = $backend.Id; executable = $backend.Path; marker = $jar },
    @{ pid = $frontend.Id; executable = $frontend.Path; marker = $vite }
) | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtime 'processes.json') -Encoding UTF8
$client = [Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(1)
$ready = $false
$deadline = [DateTime]::UtcNow.AddSeconds(45)
try {
    while ([DateTime]::UtcNow -lt $deadline) {
        $backend.Refresh()
        $frontend.Refresh()
        if ($backend.HasExited -or $frontend.HasExited) {
            throw '本工程服务在启动时退出，请检查 .runtime/backend.log 与 frontend-error.log。'
        }
        try {
            $response = $client.GetAsync('http://127.0.0.1:18182/onto/auth/me').GetAwaiter().GetResult()
            $ready = [int]$response.StatusCode -eq 401
            $response.Dispose()
            if ($ready) { break }
        } catch [Net.Http.HttpRequestException] {
            # 监听器尚未建立是启动期正常状态；总等待受 deadline 限制。
        } catch [Threading.Tasks.TaskCanceledException] {
            # 启动过程的单次探测超时，继续在总时限内等待。
        }
        Start-Sleep -Milliseconds 500
    }
} finally { $client.Dispose() }
if (-not $ready) { throw '服务在 45 秒内尚未就绪，请检查 .runtime/ 日志。' }
Write-Output '本体工具已就绪：http://127.0.0.1:15184；后端：http://127.0.0.1:18182。'
Write-Output '日志位于 .runtime/；登录账号和随机口令见 .runtime/local-env.json。'
