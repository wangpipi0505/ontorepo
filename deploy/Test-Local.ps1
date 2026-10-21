param([string]$JavaHome, [switch]$BackendOnly, [switch]$FrontendOnly)
. (Join-Path $PSScriptRoot 'Environment.ps1')
if ($BackendOnly -and $FrontendOnly) { throw '不能同时指定 BackendOnly 和 FrontendOnly。' }
$root = Get-OntoRoot
Set-OntoBuildLimits
Push-Location $root
try {
    if (-not $FrontendOnly) {
        $config = Import-OntoEnvironment
        Set-OntoJava $JavaHome
        # HTTP/MinIO 驱动会使用 JDK NIO selector；与运行服务一致使用短 ASCII 临时目录，
        # 防止 Windows 默认用户临时路径编码造成 loopback 初始化失败。
        $asciiRoot = Get-OntoAsciiRoot $config.ONTO_LOCAL_DRIVE
        $javaTemp = Join-Path $asciiRoot '.runtime/tmp'
        New-Item -ItemType Directory -Path $javaTemp -Force | Out-Null
        Invoke-OntoCommand 'mvn.cmd' @('-B', "-DargLine=-Xmx512m -Djava.io.tmpdir=$javaTemp -Djdk.net.unixdomain.tmpdir=$javaTemp", 'verify')
    }
    if (-not $BackendOnly) {
        Set-Location (Join-Path $root 'web')
        Invoke-OntoCommand 'pnpm.cmd' @('test', '--maxWorkers=2', '--minWorkers=1')
        Invoke-OntoCommand 'pnpm.cmd' @('typecheck')
        Invoke-OntoCommand 'pnpm.cmd' @('format:check')
        Invoke-OntoCommand 'pnpm.cmd' @('build')
    }
} finally { Pop-Location }
