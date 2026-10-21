# 公共环境函数只修改当前 PowerShell 进程，不修改机器级 PATH、已有服务或相邻工程配置。
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-OntoRoot {
    return [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
}

function Invoke-OntoCommand {
    param([string]$Executable, [string[]]$Arguments)
    # Windows PowerShell 5.1 会把原生命令的 stderr 记为错误；统一先放行，再按退出码判定失败。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Executable @Arguments } finally { $ErrorActionPreference = $previous }
    if ($LASTEXITCODE -ne 0) {
        throw "命令执行失败（退出码 $LASTEXITCODE）：$Executable"
    }
}

function New-OntoPassword {
    # 本地开发凭据每个工程随机生成；不在代码、终端输出或版本库中保留固定口令。
    $bytes = New-Object byte[] 24
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

function Get-OntoAsciiRoot {
    param([string]$Drive = 'O:')
    if ($Drive -notmatch '^[A-Z]:$') { throw '开发盘符必须是大写字母加冒号，例如 O:。' }
    $root = Get-OntoRoot
    $marker = Join-Path $root '.runtime/project-id.txt'
    if (-not (Test-Path -LiteralPath $marker)) {
        [Guid]::NewGuid().ToString() | Set-Content -LiteralPath $marker -Encoding ASCII
    }
    $alias = "$Drive\"
    if (Test-Path -LiteralPath $alias) {
        $mappedMarker = Join-Path $alias '.runtime/project-id.txt'
        if (-not (Test-Path -LiteralPath $mappedMarker) -or
            (Get-Content -LiteralPath $mappedMarker -Raw) -ne (Get-Content -LiteralPath $marker -Raw)) {
            throw "盘符 $Drive 已属于其他位置，拒绝修改；请指定空闲的 -Drive。"
        }
    } else {
        Invoke-OntoCommand 'subst.exe' @($Drive, $root)
    }
    return $alias
}

function Import-OntoEnvironment {
    $root = Get-OntoRoot
    $configPath = Join-Path $root '.runtime/local-env.json'
    if (-not (Test-Path -LiteralPath $configPath)) {
        throw '尚未初始化独立环境，请先运行 deploy/Initialize-Local.ps1。'
    }
    $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($item in $config.PSObject.Properties) {
        [Environment]::SetEnvironmentVariable($item.Name, [string]$item.Value, 'Process')
    }
    return $config
}

function Set-OntoJava {
    param([string]$JavaHome)
    if (-not $JavaHome) { $JavaHome = $env:ONTO_JAVA_HOME }
    if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
    if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
        throw '请通过 -JavaHome 或 ONTO_JAVA_HOME 指定 JDK 21 目录。'
    }
    $env:JAVA_HOME = $JavaHome
    $env:Path = (Join-Path $JavaHome 'bin') + [IO.Path]::PathSeparator + $env:Path
    # java -version 走 stderr；5.1 下不能按 Stop 处理，否则版本探测会被误判为失败。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $version = & (Join-Path $JavaHome 'bin/java.exe') -version 2>&1 | Out-String }
    finally { $ErrorActionPreference = $previous }
    if ($version -notmatch 'version "21\.') { throw '本工程要求 JDK 21。' }
}

function Set-OntoBuildLimits {
    # 仅约束本次脚本进程及其子进程；避免并行开发时 JVM/Node 按整机内存扩大堆。
    # 用户已显式提供堆上限时保留其配置，不修改机器环境变量。
    if ($env:MAVEN_OPTS -notmatch '-Xmx') {
        $env:MAVEN_OPTS = (($env:MAVEN_OPTS + ' -Xmx512m').Trim())
    }
    if ($env:NODE_OPTIONS -notmatch '--max-old-space-size') {
        $env:NODE_OPTIONS = (($env:NODE_OPTIONS + ' --max-old-space-size=1024').Trim())
    }
}
